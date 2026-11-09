package com.music.bitchord.playback.audio.bitperfect.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The only PCM entry to native USB. Raw sink/history buffers remain untouched,
 * so an idle resume cannot double-attenuate them. A partial native write keeps
 * its processed tail: retries never scale the same samples twice or skip gain.
 * Allocates once at open; write() does not allocate or block.
 */
internal class UsbPcmWriteGate(
    private val channels: Int,
    private val bytesPerSample: Int,
    sampleRate: Int,
    private val gain: UsbSoftwareGain,
    private val send: (ByteBuffer, Int, Int) -> Int,
) {
    init {
        require(channels in 1..2 && bytesPerSample in 2..4 && sampleRate > 0) { "Invalid USB PCM format" }
    }
    private val frameBytes = channels * bytesPerSample
    // At most 10 ms of staged audio, in addition to the bounded native queue.
    private val staged = ByteBuffer.allocateDirect(maxOf(1, sampleRate / 100) * frameBytes)
        .order(ByteOrder.LITTLE_ENDIAN).apply { limit(0) }
    private var source: ByteBuffer? = null
    private var expectedPosition = 0

    fun write(buffer: ByteBuffer): Int {
        check(buffer.remaining() % frameBytes == 0) { "Incomplete USB PCM frame" }
        if (staged.hasRemaining()) {
            check(source === buffer && expectedPosition == buffer.position()) { "USB PCM retry changed buffer" }
            check(buffer.remaining() >= staged.remaining()) { "USB PCM retry shortened buffer" }
        } else {
            val count = minOf(buffer.remaining(), staged.capacity()) / frameBytes * frameBytes
            if (count == 0) return 0
            staged.clear().limit(count)
            val from = buffer.position()
            // Absolute reads avoid a duplicate/slice allocation on every audio write.
            for (i in 0 until count) staged.put(i, buffer.get(from + i))
            gain.processInPlace(staged, 0, count)
            source = buffer
            expectedPosition = from
        }
        val count = send(staged, staged.position(), staged.remaining())
        check(count in 0..staged.remaining() && count % frameBytes == 0) { "Invalid USB PCM write count" }
        staged.position(staged.position() + count)
        buffer.position(buffer.position() + count)
        expectedPosition = buffer.position()
        if (!staged.hasRemaining()) source = null
        return count
    }

    fun flush() {
        staged.clear().limit(0)
        source = null
    }
}
