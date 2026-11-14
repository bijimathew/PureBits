package com.music.bitchord.playback.audio.bitperfect

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@androidx.annotation.OptIn(UnstableApi::class)
class BitPerfectAudioSinkTest {

    /** A DAC that plays only when the test says so, holding [capacityFrames]. */
    internal class FakeTransport(
        override val sampleRate: Int,
        override val channelCount: Int,
        override val outputBits: Int,
        private val capacityFrames: Int = 8192,
        override val driver: BitPerfectDriver = BitPerfectDriver.ANDROID_MIXER,
        private val maxWriteFrames: Int = Int.MAX_VALUE,
    ) : BitPerfectTransport {
        override val deviceName = "Fake DAC"
        override val bufferSizeFrames = capacityFrames.toLong()
        val frameBytes = channelCount * outputBits / 8
        val bytes = java.io.ByteArrayOutputStream()
        var written = 0L
        var played = 0L
        var playing = false
        var stopped = false
        var released = false
        var failOnWrite = false
        var failOnClock = false
        var writeCalls = 0
            private set
        val writtenAtPlay = mutableListOf<Long>()
        var softwareDb: Double? = null
        var playerMultiplier = 1f
            private set
        val playerVolumeChanges = mutableListOf<Float>()
        val playerVolumeAtWrite = mutableListOf<Float>()
        override val softwareVolumeDb: Double? get() = softwareDb
        override val samplesUnchanged: Boolean get() = (softwareDb == null || softwareDb == 0.0) && playerMultiplier == 1f

        override fun setPlayerVolume(volume: Float) {
            playerMultiplier = volume
            playerVolumeChanges += volume
        }

        override fun write(buffer: ByteBuffer): Int {
            writeCalls++
            if (failOnWrite) throw BitPerfectTransportException("route_changed")
            playerVolumeAtWrite += playerMultiplier
            val roomFrames = (capacityFrames - (written - played)).toInt()
            val count = minOf(roomFrames, buffer.remaining() / frameBytes, maxWriteFrames) * frameBytes
            repeat(count) { bytes.write(buffer.get().toInt()) }
            written += count / frameBytes
            return count
        }

        override fun play() { playing = true; writtenAtPlay += written }
        override fun pause() { playing = false }
        override fun flush() { written = 0; played = 0; bytes.reset() }
        override fun stop() { stopped = true }
        override fun playedFrames(): Long {
            if (failOnClock) throw BitPerfectTransportException("USB transfer clock lost")
            return played
        }
        override fun release() { released = true }

        fun advance(frames: Long) {
            played = minOf(written, played + frames)
        }
    }

    internal class FakeOutputs : BitPerfectOutputs {
        val opened = mutableListOf<FakeTransport>()
        val statuses = mutableListOf<BitPerfectOutput.Status>()
        var available = true
        var failOpen = false
        var failures = 0

        /** A DAC that stops here, as one on a capped USB-C dongle does. */
        var dacRate: Int? = null
        var dacBits: Int? = null
        var driver = BitPerfectDriver.ANDROID_MIXER
        var directRequired = false
        var softwareDb: Double? = null
        var releases = 0
        var capacityFrames = 8192
        var maxWriteFrames = Int.MAX_VALUE

        override fun requiresDirectUsb() = directRequired

        override fun canOpen(sampleRate: Int, channels: Int, sourceBits: Int, requester: Any) = available

        override fun open(sampleRate: Int, channels: Int, sourceBits: Int, requester: Any): BitPerfectTransport {
            if (failOpen) throw BitPerfectTransportException("unsupported")
            return FakeTransport(
                dacRate ?: sampleRate, channels, dacBits ?: maxOf(sourceBits, 24),
                capacityFrames = capacityFrames, driver = driver, maxWriteFrames = maxWriteFrames,
            )
                .also { it.softwareDb = softwareDb }
                .also(opened::add)
        }

        override fun release(requester: Any) { releases++ }
        override fun markFailed(sampleRate: Int, channels: Int, sourceBits: Int, reason: String) { failures++ }
        override fun publish(status: BitPerfectOutput.Status) { statuses += status }
        override fun publishIdle() = Unit
        var pendingNormal = false
        override fun normalOutputPending() = pendingNormal
    }

    private fun format(rate: Int = 44100, encoding: Int = C.ENCODING_PCM_16BIT, delay: Int = 0): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(encoding)
            .setSampleRate(rate)
            .setChannelCount(2)
            .setEncoderDelay(delay)
            .build()

    private fun config(format: Format) = AudioSink.AudioSinkConfig.Builder(format).build()

    /** [frames] stereo 16-bit frames whose samples count up from [start]. */
    private fun pcm16(frames: Int, start: Int = 0): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * 2) { buffer.putShort((start + it).toShort()) }
        buffer.flip()
        return buffer
    }

    @Test
    fun opensAtTheFirstBufferAndWritesSamplesUnchanged() {
        val outputs = FakeOutputs()
        val sink = BitPerfectAudioSink(outputs)
        sink.configure(config(format()))
        assertTrue("nothing opens before audio arrives", outputs.opened.isEmpty())

        sink.play()
        assertTrue(sink.handleBuffer(pcm16(4), 1_000_000, 1))
        val transport = outputs.opened.single()
        assertTrue(transport.playing)
        val out = ByteBuffer.wrap(transport.bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        // 16-bit samples left-aligned into the 24-bit wire format.
        repeat(8) { index ->
            val sample = (out.get().toInt() and 255) or ((out.get().toInt() and 255) shl 8) or (out.get().toInt() shl 16)
            assertEquals(index shl 8, sample)
        }
        assertTrue(outputs.statuses.last().active)
        assertTrue(outputs.statuses.last().exact)
    }

    @Test
    fun positionFollowsFramesTheDeviceActuallyPlayed() {
        val outputs = FakeOutputs()
        val sink = BitPerfectAudioSink(outputs)
        sink.configure(config(format(rate = 48000)))
        sink.play()
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, sink.getCurrentPositionUs(false))
        sink.handleBuffer(pcm16(4800), 2_000_000, 1)
        assertEquals(2_000_000, sink.getCurrentPositionUs(false))
        outputs.opened.single().advance(2400)
        assertEquals(2_050_000, sink.getCurrentPositionUs(false))
    }

    @Test
    fun sameFormatContinuesGaplesslyOnOneTransport() {
        val outputs = FakeOutputs()
        val sink = BitPerfectAudioSink(outputs)
        sink.play()
        sink.configure(config(format()))
        assertTrue(sink.handleBuffer(pcm16(441), 0, 1))
        // The next track: a new configure, same stream shape.
        sink.configure(config(format().buildUpon().setId("next").build()))
        assertTrue(sink.handleBuffer(pcm16(441), 10_000, 1))
        assertEquals(1, outputs.opened.size)
        assertFalse(outputs.opened.single().stopped)
    }

    @Test
    fun formatChangeDrainsTheOldStreamBeforeReopening() {
        val outputs = FakeOutputs()
        val sink = BitPerfectAudioSink(outputs)
        sink.play()
        sink.configure(config(format(rate = 44100)))
        assertTrue(sink.handleBuffer(pcm16(441), 0, 1))
        val first = outputs.opened.single()

        sink.configure(config(format(rate = 96000)))
        val next = pcm16(960)
        assertFalse("must wait for the 44.1 kHz tail", sink.handleBuffer(next, 10_000, 1))
        assertTrue(first.stopped)
        assertEquals(1, outputs.opened.size)

        first.advance(441)
        assertTrue(sink.handleBuffer(next, 10_000, 1))
        assertTrue(first.released)
        assertEquals(96000, outputs.opened.last().sampleRate)
        assertEquals(10_000, sink.getCurrentPositionUs(false))
    }

    @Test
    fun endsOnlyOnceTheDeviceHasPlayedEverything() {
        val outputs = FakeOutputs()
        val sink = BitPerfectAudioSink(outputs)
        sink.play()
        sink.configure(config(format()))
        sink.handleBuffer(pcm16(100), 0, 1)
        sink.playToEndOfStream()
        val transport = outputs.opened.single()
        assertTrue(transport.stopped)
        assertFalse(sink.isEnded())
        assertTrue(sink.hasPendingData())
        transport.advance(100)
        assertTrue(sink.isEnded())
    }

    @Test
    fun backpressureKeepsTheRemainderForTheNextCall() {
        val outputs = FakeOutputs()
        val sink = BitPerfectAudioSink(outputs)
        sink.play()
        sink.configure(config(format()))
        val big = pcm16(20_000)
        assertFalse(sink.handleBuffer(big, 0, 1))
        val transport = outputs.opened.single()
        assertEquals(8192, transport.written)
        transport.advance(8192)
        // Re-handed the same buffer, as the renderer does.
        while (!sink.handleBuffer(big, 0, 1)) transport.advance(8192)
        assertEquals(20_000L, transport.written)
        assertFalse(big.hasRemaining())
    }

    @Test(timeout = 1000)
    fun directUsbTenMillisecondWritesFillAvailableBufferInOneRendererCall() {
        val outputs = FakeOutputs().apply {
            driver = BitPerfectDriver.USB_DIRECT
            softwareDb = -40.0
            dacBits = 16
            maxWriteFrames = 441
        }
        val sink = BitPerfectAudioSink(outputs)
        sink.configure(config(format(rate = 44100)))
        sink.play()
        val source = pcm16(4096)
        val expected = bytes(source)

        assertTrue("small successful writes must not yield back to the renderer", sink.handleBuffer(source, 0, 1))
        val transport = outputs.opened.single()
        assertEquals(4096L, transport.written)
        assertEquals(listOf(4096L), transport.writtenAtPlay)
        assertTrue(transport.writeCalls in 2..11)
        assertFalse(source.hasRemaining())
        assertArrayEquals(expected, transport.bytes.toByteArray())
    }

    @Test(timeout = 1000)
    fun directUsbNinetySixKhzPacked24BitChunkAlsoFillsBeforeStarting() {
        val outputs = FakeOutputs().apply {
            driver = BitPerfectDriver.USB_DIRECT
            softwareDb = -40.0
            dacBits = 24
            maxWriteFrames = 960
        }
        val sink = BitPerfectAudioSink(outputs)
        sink.configure(config(format(rate = 96000, encoding = C.ENCODING_PCM_24BIT)))
        sink.play()
        val source = pcm24(4096)
        val expected = bytes(source)

        assertTrue(sink.handleBuffer(source, 0, 1))
        val transport = outputs.opened.single()
        assertEquals(4096L, transport.written)
        assertEquals(listOf(4096L), transport.writtenAtPlay)
        assertTrue(transport.writeCalls in 2..6)
        assertArrayEquals(expected, transport.bytes.toByteArray())
        assertEquals(96000, outputs.statuses.last().sampleRateHz)
    }

    @Test(timeout = 1000)
    fun smallDirectWritesStopAtRealBackpressureAndResumeWithoutDroppingOrDuplicatingPcm() {
        val outputs = FakeOutputs().apply {
            driver = BitPerfectDriver.USB_DIRECT
            dacBits = 16
            maxWriteFrames = 441
            capacityFrames = 882
        }
        val sink = BitPerfectAudioSink(outputs)
        sink.configure(config(format()))
        sink.play()
        val source = pcm16(4096)
        val expected = bytes(source)

        assertFalse(sink.handleBuffer(source, 2_000_000, 1))
        val transport = outputs.opened.single()
        assertEquals(882L, transport.written)
        assertEquals(listOf(882L), transport.writtenAtPlay)
        assertTrue("a full native queue must stop the write loop", transport.writeCalls <= 3)
        val beforeRetry = transport.writeCalls
        assertFalse(sink.handleBuffer(source, 2_000_000, 1))
        assertEquals("a zero write yields immediately", 1, transport.writeCalls - beforeRetry)
        assertEquals(882L, transport.written)

        var complete = false
        repeat(5) {
            if (!complete) {
                transport.advance(882)
                complete = sink.handleBuffer(source, 2_000_000, 1)
            }
        }
        assertTrue("the pending PCM must finish as native capacity returns", complete)
        assertEquals(4096L, transport.written)
        assertFalse(source.hasRemaining())
        assertArrayEquals(expected, transport.bytes.toByteArray())
    }

    @Test(timeout = 1000)
    fun idleUsbResumeDrainsSmallWritesOfTheRawTailBeforePlayingIt() {
        var now = 0L
        val outputs = FakeOutputs().apply {
            driver = BitPerfectDriver.USB_DIRECT
            softwareDb = -40.0
            dacBits = 16
            maxWriteFrames = 441
        }
        val sink = BitPerfectAudioSink(outputs) { now }
        sink.configure(config(format()))
        sink.play()
        val source = pcm16(4096)
        val original = bytes(source)
        assertTrue(sink.handleBuffer(source, 0, 1))
        val first = outputs.opened.single()
        first.advance(1000)
        sink.pause()
        val next = pcm16(441, start = 8192)
        val nextBytes = bytes(next)
        now = BitPerfectAudioSink.IDLE_RELEASE_MS
        assertFalse(sink.handleBuffer(next, 100_000, 1))
        assertTrue(first.released)

        sink.play()
        assertTrue(sink.handleBuffer(next, 100_000, 1))
        val resumed = outputs.opened.last()
        assertEquals(2, outputs.opened.size)
        assertEquals(listOf(3096L), resumed.writtenAtPlay)
        assertEquals(3537L, resumed.written)
        assertEquals(-40.0, resumed.softwareVolumeDb!!, 0.0)
        assertArrayEquals(original.copyOfRange(1000 * 4, original.size) + nextBytes, resumed.bytes.toByteArray())
    }

    private fun bytes(buffer: ByteBuffer): ByteArray = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }

    private fun pcm24(frames: Int): ByteBuffer = ByteBuffer.allocateDirect(frames * 6).apply {
        repeat(frames * 2) { index ->
            val sample = index * 257 - 1_048_576
            put(sample.toByte())
            put((sample shr 8).toByte())
            put((sample shr 16).toByte())
        }
        flip()
    }

    @Test
    fun flushKeepsTheDeviceOpenAndRestartsTheClock() {
        val outputs = FakeOutputs()
        val sink = BitPerfectAudioSink(outputs)
        sink.play()
        sink.configure(config(format()))
        sink.handleBuffer(pcm16(441), 0, 1)
        sink.flush()
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, sink.getCurrentPositionUs(false))
        sink.handleBuffer(pcm16(441), 30_000_000, 1)
        assertEquals(1, outputs.opened.size)
        assertEquals(30_000_000, sink.getCurrentPositionUs(false))
    }

    @Test
    fun lossyPrimedStreamsAreLeftToTheOrdinaryPath() {
        val sink = BitPerfectAudioSink(FakeOutputs())
        assertFalse(sink.canAccept(format(delay = 1105)))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(format(delay = 1105)))
        assertTrue(sink.canAccept(format()))
    }

    @Test
    fun floatThatIsNotIntegerPcmIsReportedNotExact() {
        val outputs = FakeOutputs()
        val sink = BitPerfectAudioSink(outputs)
        sink.play()
        sink.configure(config(format(encoding = C.ENCODING_PCM_FLOAT)))
        val buffer = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.1234567f).putFloat(-0.3f)
        buffer.flip()
        sink.handleBuffer(buffer, 0, 1)
        assertFalse(outputs.statuses.last().exact)
    }

    @Test
    fun trackBeyondTheDacIsConvertedAndNotCalledBitPerfect() {
        val outputs = FakeOutputs().apply { dacRate = 48000; dacBits = 16 }
        val sink = BitPerfectAudioSink(outputs)
        sink.play()
        sink.configure(config(format(rate = 96000, encoding = C.ENCODING_PCM_24BIT)))
        val frames = 9600
        val buffer = ByteBuffer.allocateDirect(frames * 6).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(ByteArray(frames * 6))
        buffer.flip()
        assertTrue(sink.handleBuffer(buffer, 0, 1))
        sink.playToEndOfStream()

        val transport = outputs.opened.single()
        assertEquals(frames / 2L, transport.written)
        val status = outputs.statuses.last()
        assertTrue(status.dacLimited)
        assertFalse(status.exact)
        assertEquals(48000, status.sampleRateHz)
        assertEquals(16, status.outputBits)
        assertEquals(96000, status.sourceRateHz)
        assertEquals(24, status.sourceBits)

        // The clock runs at the DAC's rate: half the output frames is 50 ms of the track.
        transport.advance(2400)
        assertEquals(50_000, sink.getCurrentPositionUs(false))
        assertFalse(sink.isEnded())
        transport.advance(2400)
        assertTrue(sink.isEnded())
    }

    @Test
    fun trackTheDacCanTakeIsNotConverted() {
        val outputs = FakeOutputs().apply { dacRate = 44100; dacBits = 24 }
        val sink = BitPerfectAudioSink(outputs)
        sink.play()
        sink.configure(config(format(rate = 44100)))
        sink.handleBuffer(pcm16(4), 0, 1)
        assertFalse(outputs.statuses.last().dacLimited)
        assertTrue(outputs.statuses.last().exact)
    }

    @Test
    fun softwareAttenuationIsExclusiveButNeverReportedBitPerfect() {
        val outputs = FakeOutputs().apply { driver = BitPerfectDriver.USB_DIRECT; softwareDb = -40.0 }
        val sink = BitPerfectAudioSink(outputs)
        sink.configure(config(format(rate = 44100)))
        sink.play()
        sink.handleBuffer(pcm16(4), 0, 1)
        val status = outputs.statuses.last()
        assertTrue(status.active)
        assertTrue(status.sourceExact)
        assertFalse(status.exact)
        assertEquals("software", status.volumeControl)
        assertEquals(-40.0, status.softwareVolumeDb!!, 0.0)
        assertEquals(44100, status.sampleRateHz)
        assertEquals(44100, status.sourceRateHz)
        assertFalse(status.dacLimited)
    }

    @Test
    fun softwareUnityCanBeExactAndAttenuationUpdatesTheVerdict() {
        val outputs = FakeOutputs().apply { driver = BitPerfectDriver.USB_DIRECT; softwareDb = 0.0 }
        val sink = BitPerfectAudioSink(outputs)
        sink.configure(config(format()))
        sink.play()
        sink.handleBuffer(pcm16(4), 0, 1)
        assertTrue(outputs.statuses.last().exact)
        val transport = outputs.opened.single()
        transport.softwareDb = -20.0
        sink.handleBuffer(pcm16(4), 91, 1)
        assertFalse(outputs.statuses.last().exact)
        assertEquals(-20.0, outputs.statuses.last().softwareVolumeDb!!, 0.0)
        transport.softwareDb = 0.0
        sink.handleBuffer(pcm16(4), 182, 1)
        assertTrue(outputs.statuses.last().exact)
    }

    @Test
    fun pauseSeekTrackAndRateChangesKeepTheTransportVolumePolicy() {
        val outputs = FakeOutputs().apply { driver = BitPerfectDriver.USB_DIRECT; softwareDb = -40.0 }
        val sink = BitPerfectAudioSink(outputs)
        sink.configure(config(format(rate = 44100)))
        sink.play()
        sink.handleBuffer(pcm16(4), 0, 1)
        val first = outputs.opened.single()
        sink.pause()
        assertFalse(first.playing)
        assertTrue(first.released)
        sink.flush()
        sink.play()
        sink.handleBuffer(pcm16(4), 10_000_000, 1)
        assertEquals(-40.0, first.softwareVolumeDb!!, 0.0)
        assertEquals(2, outputs.opened.size)
        val resumed = outputs.opened.last()
        assertEquals(-40.0, resumed.softwareVolumeDb!!, 0.0)
        sink.configure(config(format().buildUpon().setId("next").build()))
        sink.handleBuffer(pcm16(4), 10_000_091, 1)
        assertEquals(2, outputs.opened.size)
        resumed.advance(resumed.written)
        sink.configure(config(format(rate = 48000)))
        assertTrue(sink.handleBuffer(pcm16(4), 10_000_182, 1))
        assertEquals(3, outputs.opened.size)
        val next = outputs.opened.last()
        assertEquals(48000, next.sampleRate)
        assertEquals(-40.0, next.softwareVolumeDb!!, 0.0)
        assertFalse(outputs.statuses.last().exact)
    }

    @Test
    fun failedOpenIsRecordedAndRaisedAsRecoverable() {
        val outputs = FakeOutputs().apply { failOpen = true }
        val sink = BitPerfectAudioSink(outputs)
        sink.configure(config(format()))
        try {
            sink.handleBuffer(pcm16(10), 0, 1)
            fail("expected an initialization failure")
        } catch (error: AudioSink.InitializationException) {
            assertTrue(error.isRecoverable)
        }
        assertEquals(1, outputs.failures)
    }

    @Test
    fun routeLossSurfacesAsRecoverableWriteError() {
        val outputs = FakeOutputs()
        val sink = BitPerfectAudioSink(outputs)
        sink.play()
        sink.configure(config(format()))
        sink.handleBuffer(pcm16(10), 0, 1)
        outputs.opened.single().failOnWrite = true
        try {
            sink.handleBuffer(pcm16(10), 226, 1)
            fail("expected a write failure")
        } catch (error: AudioSink.WriteException) {
            assertTrue(error.isRecoverable)
        }
    }

    // ---- Idle release ---------------------------------------------------------------------

    @Test
    fun directPauseImmediatelyReleasesTheDeviceAndResumesAtTheSameFrame() {
        var now = 0L
        val outputs = FakeOutputs().apply { driver = BitPerfectDriver.USB_DIRECT }
        val sink = BitPerfectAudioSink(outputs) { now }
        sink.configure(config(format(rate = 48000)))
        sink.play()
        assertTrue(sink.handleBuffer(pcm16(4800), 0, 1))
        val first = outputs.opened.single()
        first.advance(1200)
        sink.pause()
        assertTrue(first.released)
        assertEquals(1, outputs.releases)

        // Media3 can prepare more data while paused without reclaiming the DAC
        // or consuming its decoder-owned buffer. It must still report ready.
        now = BitPerfectAudioSink.IDLE_RELEASE_MS - 1
        val more = pcm16(4800, start = 9600)
        assertFalse(sink.handleBuffer(more, 100_000, 1))
        assertEquals(0, more.position())

        now = BitPerfectAudioSink.IDLE_RELEASE_MS
        assertFalse(sink.handleBuffer(more, 100_000, 1))
        assertTrue(first.released)
        assertEquals(1, outputs.releases)
        assertEquals(1, outputs.opened.size)
        assertTrue(sink.hasPendingData())
        assertFalse(sink.isEnded())

        sink.play()
        // The unplayed 3600 frames precede the new 4800; 208 wait for capacity.
        assertFalse(sink.handleBuffer(more, 100_000, 1))
        val second = outputs.opened.last()
        assertEquals(2, outputs.opened.size)
        assertTrue(second.playing)
        assertEquals(25_000, sink.getCurrentPositionUs(false))
        // The new device starts with exactly what the old one had not played.
        val kept = first.bytes.toByteArray().copyOfRange(1200 * first.frameBytes, first.bytes.size())
        assertTrue(kept.contentEquals(second.bytes.toByteArray().copyOfRange(0, kept.size)))

        second.advance(2400)
        assertTrue(sink.handleBuffer(more, 100_000, 1))
        assertEquals(8192L + 208, second.written)
        // This fake DAC accepts PCM24: PCM16 samples are left-aligned in it.
        val expectedNew = ByteBuffer.allocate(4800 * first.frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(4800 * 2) { expectedNew.put(0).putShort((9600 + it).toShort()) }
        assertTrue((kept + expectedNew.array()).contentEquals(second.bytes.toByteArray()))
        assertEquals(75_000, sink.getCurrentPositionUs(false))
    }

    @Test
    fun pausedMixerHandsDeviceBackAndDoesNotReopenUntilPlay() {
        var now = 0L
        val outputs = FakeOutputs()
        val sink = BitPerfectAudioSink(outputs) { now }
        sink.configure(config(format()))
        sink.play()
        sink.handleBuffer(pcm16(441), 0, 1)
        sink.pause()
        assertTrue(outputs.opened.single().released)
        now = 10 * BitPerfectAudioSink.IDLE_RELEASE_MS
        val more = pcm16(441)
        assertFalse(sink.handleBuffer(more, 10_000, 1))
        assertEquals(1, outputs.opened.size)
        assertEquals(0, more.position())
        assertTrue(sink.hasPendingData())
        sink.play()
        assertTrue(sink.handleBuffer(more, 10_000, 1))
        assertEquals(2, outputs.opened.size)
        assertTrue(outputs.opened.last().playing)
    }

    @Test
    fun seekDuringALongPauseReopensOnlyAtPlay() {
        var now = 0L
        val outputs = FakeOutputs().apply { driver = BitPerfectDriver.AAUDIO_EXCLUSIVE }
        val sink = BitPerfectAudioSink(outputs) { now }
        sink.configure(config(format()))
        sink.play()
        sink.handleBuffer(pcm16(441), 0, 1)
        sink.pause()
        now = BitPerfectAudioSink.IDLE_RELEASE_MS
        sink.handleBuffer(pcm16(441), 10_000, 1)
        assertTrue(outputs.opened.single().released)

        sink.flush()
        val seeked = pcm16(441)
        assertFalse(sink.handleBuffer(seeked, 60_000_000, 1))
        assertEquals(1, outputs.opened.size)

        sink.play()
        assertTrue(sink.handleBuffer(seeked, 60_000_000, 1))
        assertEquals(2, outputs.opened.size)
        assertEquals(441L, outputs.opened.last().written)
        assertEquals(60_000_000, sink.getCurrentPositionUs(false))
    }

    @Test
    fun recentBytesKeepsTheNewestAcrossTheWrap() {
        val recent = RecentBytes(8)
        val source = ByteBuffer.allocateDirect(12)
        repeat(12) { source.put(it.toByte()) }
        recent.record(source, 0, 5)
        recent.record(source, 5, 7)
        assertEquals(8, recent.size)
        val out = ByteBuffer.allocate(6)
        recent.copyLast(6, out)
        assertTrue(byteArrayOf(6, 7, 8, 9, 10, 11).contentEquals(out.array()))
    }

    // ---- Routing --------------------------------------------------------------------------

    /** Just enough of an AudioSink to see what the router hands it. */
    private class RecordingSink : AudioSink by NoOpSink() {
        var configured: Format? = null
        var buffers = 0
        var pending = false
        var resets = 0
        var played = false

        override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) { configured = audioSinkConfig.format }
        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            buffers++
            buffer.position(buffer.limit())
            return true
        }
        override fun hasPendingData() = pending
        override fun isEnded() = !pending
        override fun reset() { resets++ }
        override fun play() { played = true }
    }

    @Test
    fun routerForwardsEverythingWhileTheOutputIsUnavailable() {
        val outputs = FakeOutputs().apply { available = false }
        val normal = RecordingSink()
        val router = BitPerfectRoutingSink(normal, BitPerfectAudioSink(outputs))
        router.configure(config(format()))
        assertTrue(router.handleBuffer(pcm16(10), 0, 1))
        assertNotNull(normal.configured)
        assertEquals(1, normal.buffers)
        assertFalse(router.isBitPerfect)
        assertTrue(outputs.opened.isEmpty())
    }

    @Test
    fun routerFallsBackToNormalWhenTheOutputCannotOpen() {
        val outputs = FakeOutputs().apply { failOpen = true }
        val normal = RecordingSink()
        val router = BitPerfectRoutingSink(normal, BitPerfectAudioSink(outputs))
        router.play()
        val format = format()
        router.configure(config(format))
        assertTrue(router.isBitPerfect)
        assertTrue(router.handleBuffer(pcm16(10), 0, 1))
        assertFalse(router.isBitPerfect)
        assertSame(format, normal.configured)
        assertEquals(1, normal.buffers)
        assertTrue(normal.played)
    }

    @Test
    fun strictDirectUsbUnavailableNeverConfiguresOrWritesNormalOutput() {
        val outputs = FakeOutputs().apply { available = false; directRequired = true }
        val normal = RecordingSink()
        val router = BitPerfectRoutingSink(normal, BitPerfectAudioSink(outputs))
        try {
            router.configure(config(format()))
            fail("Direct USB must fail closed while unavailable")
        } catch (error: AudioSink.ConfigurationException) {
            assertTrue(error.cause is DirectUsbSafetyException)
        }
        assertEquals(null, normal.configured)
        assertEquals(0, normal.buffers)
        assertTrue(outputs.opened.isEmpty())
    }

    @Test
    fun strictDirectUsbUnsupportedFormatNeverUsesNormalOutput() {
        val outputs = FakeOutputs().apply { directRequired = true }
        val normal = RecordingSink()
        val router = BitPerfectRoutingSink(normal, BitPerfectAudioSink(outputs))
        val unsupported = format().buildUpon().setChannelCount(6).build()
        try {
            router.configure(config(unsupported))
            fail("Unsupported direct format must fail closed")
        } catch (error: AudioSink.ConfigurationException) {
            assertTrue(error.cause is DirectUsbSafetyException)
        }
        assertEquals(null, normal.configured)
        assertEquals(0, normal.buffers)
    }

    @Test
    fun strictDirectUsbFailedOpenSurfacesNonrecoverableWithoutNormalFallback() {
        val outputs = FakeOutputs().apply { directRequired = true; failOpen = true }
        val normal = RecordingSink()
        val router = BitPerfectRoutingSink(normal, BitPerfectAudioSink(outputs))
        router.play()
        router.configure(config(format()))
        val samples = pcm16(4)
        try {
            router.handleBuffer(samples, 0, 1)
            fail("Opening failure must stop direct playback")
        } catch (error: AudioSink.InitializationException) {
            assertFalse(error.isRecoverable)
        }
        assertEquals(0, samples.position())
        assertEquals(null, normal.configured)
        assertEquals(0, normal.buffers)
        assertEquals(1, outputs.failures)
    }

    @Test
    fun strictDirectUsbWriteFailureReleasesRouteWithoutNormalFallback() {
        val outputs = FakeOutputs().apply { directRequired = true; driver = BitPerfectDriver.USB_DIRECT; softwareDb = -40.0 }
        val normal = RecordingSink()
        val router = BitPerfectRoutingSink(normal, BitPerfectAudioSink(outputs))
        router.configure(config(format()))
        router.play()
        router.handleBuffer(pcm16(4), 0, 1)
        val transport = outputs.opened.single()
        transport.failOnWrite = true
        try {
            router.handleBuffer(pcm16(4), 91, 1)
            fail("A lost direct stream must stop instead of substituting Android volume")
        } catch (error: AudioSink.WriteException) {
            assertFalse(error.isRecoverable)
        }
        assertTrue(transport.released)
        assertEquals(null, normal.configured)
        assertEquals(0, normal.buffers)
    }

    @Test
    fun changingSelectedModeCannotEnableFallbackForTheFailingDirectStream() {
        val outputs = FakeOutputs().apply { directRequired = true; driver = BitPerfectDriver.USB_DIRECT }
        val normal = RecordingSink()
        val router = BitPerfectRoutingSink(normal, BitPerfectAudioSink(outputs))
        router.configure(config(format()))
        router.play()
        router.handleBuffer(pcm16(4), 0, 1)
        val transport = outputs.opened.single()
        // The preference collector can move before the already configured stream is rebuilt.
        outputs.directRequired = false
        transport.failOnWrite = true
        try {
            router.handleBuffer(pcm16(4), 91, 1)
            fail("The current Direct USB stream must retain its fail-closed policy")
        } catch (error: AudioSink.WriteException) {
            assertFalse(error.isRecoverable)
            assertTrue(error.cause is DirectUsbSafetyException)
        }
        assertTrue(transport.released)
        assertEquals(0, normal.buffers)
        assertEquals(null, normal.configured)

        // An explicit new configuration after selecting another output can use Android again.
        outputs.available = false
        router.configure(config(format()))
        assertTrue(router.handleBuffer(pcm16(4), 182, 1))
        assertEquals(1, normal.buffers)
        assertFalse(router.isBitPerfect)
    }

    @Test
    fun clockFailureAfterTheFinalBufferSurfacesAtEosInsteadOfHangingOrFallingBack() {
        val outputs = FakeOutputs().apply { directRequired = true; driver = BitPerfectDriver.USB_DIRECT }
        val normal = RecordingSink()
        val router = BitPerfectRoutingSink(normal, BitPerfectAudioSink(outputs))
        router.configure(config(format()))
        router.play()
        router.handleBuffer(pcm16(4), 0, 1)
        val transport = outputs.opened.single()
        transport.failOnClock = true
        // Media3 clock polling cannot throw a checked audio write error, so it defers one.
        router.getCurrentPositionUs(false)
        try {
            router.playToEndOfStream()
            fail("A fatal clock error must surface even when no further decoded buffers arrive")
        } catch (error: AudioSink.WriteException) {
            assertFalse(error.isRecoverable)
            assertTrue(error.cause is DirectUsbSafetyException)
        }
        assertTrue(transport.released)
        assertEquals(0, normal.buffers)
    }

    @Test
    fun playerMuteSetBeforeTheFirstBufferReachesTheTransportBeforeAnyWrite() {
        val outputs = FakeOutputs().apply { directRequired = true; driver = BitPerfectDriver.USB_DIRECT; softwareDb = -40.0 }
        val router = BitPerfectRoutingSink(RecordingSink(), BitPerfectAudioSink(outputs))
        router.setVolume(0f)
        router.configure(config(format()))
        router.play()
        assertTrue(router.handleBuffer(pcm16(4), 0, 1))
        val transport = outputs.opened.single()
        assertTrue(transport.playerVolumeChanges.isNotEmpty())
        assertEquals(0f, transport.playerVolumeChanges.first(), 0f)
        assertTrue(transport.playerVolumeAtWrite.isNotEmpty())
        assertTrue(transport.playerVolumeAtWrite.all { it == 0f })
        assertEquals(-40.0, transport.softwareVolumeDb!!, 0.0)
        assertFalse(outputs.statuses.last().exact)
    }

    @Test
    fun audioFocusDuckingUpdatesThePlayerMultiplierWithoutChangingTheUserGain() {
        val outputs = FakeOutputs().apply { directRequired = true; driver = BitPerfectDriver.USB_DIRECT; softwareDb = -40.0 }
        val router = BitPerfectRoutingSink(RecordingSink(), BitPerfectAudioSink(outputs))
        router.configure(config(format()))
        router.play()
        router.handleBuffer(pcm16(4), 0, 1)
        val transport = outputs.opened.single()
        router.setVolume(0.2f)
        router.handleBuffer(pcm16(4), 91, 1)
        assertEquals(0.2f, transport.playerMultiplier, 0f)
        assertEquals(0.2f, transport.playerVolumeAtWrite.last(), 0f)
        assertEquals(-40.0, transport.softwareVolumeDb!!, 0.0)
        router.setVolume(1f)
        router.handleBuffer(pcm16(4), 182, 1)
        assertEquals(1f, transport.playerMultiplier, 0f)
        assertEquals(-40.0, transport.softwareVolumeDb!!, 0.0)
        assertEquals(listOf(1f, 0.2f, 1f), transport.playerVolumeChanges)
    }

    @Test
    fun routerLetsTheOutgoingSinkFinishBeforeSwitching() {
        val outputs = FakeOutputs()
        val normal = RecordingSink()
        val router = BitPerfectRoutingSink(normal, BitPerfectAudioSink(outputs))
        router.play()
        // A lossy stream on the normal path, still playing out its tail...
        router.configure(config(format(delay = 1105)))
        router.handleBuffer(pcm16(10), 0, 1)
        normal.pending = true
        // ...when a lossless track arrives.
        router.configure(config(format()))
        assertFalse(router.handleBuffer(pcm16(10), 1_000, 1))
        assertTrue(outputs.opened.isEmpty())
        normal.pending = false
        assertTrue(router.handleBuffer(pcm16(10), 1_000, 1))
        assertEquals(1, normal.resets)
        assertEquals(1, outputs.opened.size)
    }

    /** A local 5.1 file (a Dolby track decoded to PCM, say) after a lossless stereo one. */
    @Test
    fun multichannelLocalTrackPlaysOnNormalOutputAfterBitPerfect() {
        val outputs = FakeOutputs()
        val normal = RecordingSink()
        val router = BitPerfectRoutingSink(normal, BitPerfectAudioSink(outputs))
        router.play()
        router.configure(config(format()))
        assertTrue(router.handleBuffer(pcm16(10), 0, 1))
        assertTrue(router.isBitPerfect)

        val surround = format().buildUpon().setChannelCount(6).build()
        router.configure(config(surround))
        assertFalse(router.isBitPerfect)
        val sixChannels = ByteBuffer.allocateDirect(12 * 4).order(ByteOrder.LITTLE_ENDIAN)
        // The stereo tail plays out on the DAC first.
        assertFalse(router.handleBuffer(sixChannels, 1_000, 1))
        outputs.opened.single().advance(10)
        assertTrue(router.handleBuffer(sixChannels, 1_000, 1))
        assertSame(surround, normal.configured)
        assertTrue(outputs.opened.single().released)
    }

    @Test
    fun normalOutputWaitsWhileTheDacReturnsToAndroid() {
        val outputs = FakeOutputs().apply { available = false; pendingNormal = true }
        val normal = RecordingSink()
        val router = BitPerfectRoutingSink(normal, BitPerfectAudioSink(outputs))
        router.configure(config(format()))
        assertFalse(router.handleBuffer(pcm16(10), 0, 1))
        assertEquals(0, normal.buffers)
        outputs.pendingNormal = false
        assertTrue(router.handleBuffer(pcm16(10), 0, 1))
        assertEquals(1, normal.buffers)
    }
}

/** Every AudioSink method as a harmless default, for fakes to override selectively. */
@androidx.annotation.OptIn(UnstableApi::class)
private class NoOpSink : AudioSink {
    override fun setListener(listener: AudioSink.Listener) = Unit
    override fun supportsFormat(format: Format) = true
    override fun getFormatSupport(format: Format) = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    override fun getCurrentPositionUs(sourceEnded: Boolean) = AudioSink.CURRENT_POSITION_NOT_SET
    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) = Unit
    override fun play() = Unit
    override fun handleDiscontinuity() = Unit
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int) = true
    override fun playToEndOfStream() = Unit
    override fun isEnded() = true
    override fun hasPendingData() = false
    override fun setPlaybackParameters(playbackParameters: androidx.media3.common.PlaybackParameters) = Unit
    override fun getPlaybackParameters() = androidx.media3.common.PlaybackParameters.DEFAULT
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = Unit
    override fun getSkipSilenceEnabled() = false
    override fun setAudioAttributes(audioAttributes: androidx.media3.common.AudioAttributes) = Unit
    override fun getAudioAttributes() = androidx.media3.common.AudioAttributes.DEFAULT
    override fun setAudioSessionId(audioSessionId: Int) = Unit
    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) = Unit
    override fun getAudioTrackBufferSizeUs() = C.TIME_UNSET
    override fun enableTunnelingV21() = Unit
    override fun disableTunneling() = Unit
    override fun setVolume(volume: Float) = Unit
    override fun pause() = Unit
    override fun flush() = Unit
    override fun reset() = Unit
}
