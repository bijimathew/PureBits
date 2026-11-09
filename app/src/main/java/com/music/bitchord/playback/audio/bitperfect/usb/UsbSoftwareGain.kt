/*
 * Copyright (C) 2026 PureBits contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.music.bitchord.playback.audio.bitperfect.usb

import java.nio.ByteBuffer
import kotlin.math.round

/**
 * Last-stage PCM attenuation before direct USB transmission. Never use this on DoP/DSD data.
 *
 * A single audio thread owns [processInPlace]; control threads may call [setTargetDb]. Each
 * request publishes its dB and linear gain together, and a processing call snapshots that request.
 * Gain advances once per complete interleaved frame, so every channel receives the same ramp.
 * There are no allocations or blocking calls on the processing path and no sample-rate changes.
 *
 * The caller must process each byte only once (including across partial USB writes), and must
 * not transmit a buffer if processing fails. Buffers and their position/limit/order are retained.
 */
internal class UsbSoftwareGain(
    sampleRate: Int,
    private val channels: Int,
    private val format: Format,
    initialDb: Double = UsbSoftwareVolumePolicy.MUTE_DB,
    rampMs: Int = 10,
) {
    enum class Format(val sampleBytes: Int) {
        PCM_16(2), PCM_24(3), PCM_32(4), FLOAT_32(4);

        companion object {
            fun integer(bits: Int): Format = when (bits) {
                16 -> PCM_16
                24 -> PCM_24
                32 -> PCM_32
                else -> throw IllegalArgumentException("Unsupported USB PCM depth: $bits")
            }
        }
    }

    private data class Request(val db: Double, val gain: Double)

    init {
        require(sampleRate > 0) { "Invalid USB sample rate" }
        require(channels in 1..2) { "Unsupported USB channel count" }
        require(rampMs in 0..1000) { "Invalid volume ramp" }
    }

    private val frameBytes = format.sampleBytes * channels
    private val rampFrames = ((sampleRate.toLong() * rampMs + 999) / 1000).coerceAtLeast(1).toInt()
    private val immediate = rampMs == 0
    @Volatile private var requested = request(initialDb)
    private var appliedRequest = requested
    private var targetGain = requested.gain
    private var step = 0.0
    private var remainingRampFrames = 0

    /** Read-only diagnostic snapshot; processing state is never reset by a pause, seek or flush. */
    @Volatile var currentGain = requested.gain
        private set

    val targetDb: Double get() = requested.db
    val atUnity: Boolean get() = requested.gain == 1.0 && currentGain == 1.0

    fun setTargetDb(db: Double) {
        val normalized = UsbSoftwareVolumePolicy.normalizeDb(db)
        if (requested.db != normalized) requested = request(normalized)
    }

    /** Scales complete little-endian frames, without advancing or changing the buffer's view. */
    fun processInPlace(buffer: ByteBuffer, offset: Int = buffer.position(), count: Int = buffer.remaining()) {
        require(!buffer.isReadOnly) { "USB gain requires writable PCM" }
        require(offset >= 0 && count >= 0 && offset <= buffer.limit() - count) { "Invalid PCM buffer range" }
        require(count % frameBytes == 0) { "Incomplete USB PCM frame" }
        val next = requested
        if (next.db != appliedRequest.db) {
            appliedRequest = next
            targetGain = next.gain
            if (targetGain == 0.0 || immediate) {
                // Mute takes effect immediately, including invalid control requests.
                currentGain = targetGain
                remainingRampFrames = 0
            } else {
                remainingRampFrames = rampFrames
                step = (targetGain - currentGain) / rampFrames
            }
        }
        // Exact integer unity path, including all 32 source bits and container padding.
        if (currentGain == 1.0 && targetGain == 1.0 && format != Format.FLOAT_32) return

        val end = offset + count
        var at = offset
        var gain = currentGain
        while (at < end) {
            if (remainingRampFrames > 0) {
                remainingRampFrames--
                gain = if (remainingRampFrames == 0) targetGain else (gain + step).coerceIn(0.0, 1.0)
            }
            repeat(channels) {
                when (format) {
                    Format.PCM_16 -> {
                        val sample = (u(buffer, at) or (buffer.get(at + 1).toInt() shl 8)).toShort().toInt()
                        putInteger(buffer, at, scaled(sample, gain, Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()), 2)
                    }
                    Format.PCM_24 -> {
                        val sample = u(buffer, at) or (u(buffer, at + 1) shl 8) or (buffer.get(at + 2).toInt() shl 16)
                        putInteger(buffer, at, scaled(sample, gain, -8_388_608, 8_388_607), 3)
                    }
                    Format.PCM_32 -> {
                        val sample = read32(buffer, at)
                        putInteger(buffer, at, scaled(sample, gain, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()), 4)
                    }
                    Format.FLOAT_32 -> {
                        val sample = Float.fromBits(read32(buffer, at))
                        // Corrupt decoder values must never become an infinite or full-scale output.
                        val value = if (!sample.isFinite() || gain == 0.0) 0f else (sample.toDouble() * gain).coerceIn(-1.0, 1.0).toFloat()
                        putInteger(buffer, at, value.toRawBits(), 4)
                    }
                }
                at += format.sampleBytes
            }
        }
        currentGain = gain
    }

    private fun request(db: Double): Request {
        val normalized = UsbSoftwareVolumePolicy.normalizeDb(db)
        return Request(normalized, UsbSoftwareVolumePolicy.linearGain(normalized))
    }

    private fun scaled(sample: Int, gain: Double, min: Long, max: Long): Int =
        if (gain == 0.0) 0 else round(sample.toDouble() * gain).toLong().coerceIn(min, max).toInt()

    private fun u(buffer: ByteBuffer, at: Int): Int = buffer.get(at).toInt() and 255

    private fun read32(buffer: ByteBuffer, at: Int): Int =
        u(buffer, at) or (u(buffer, at + 1) shl 8) or (u(buffer, at + 2) shl 16) or (buffer.get(at + 3).toInt() shl 24)

    private fun putInteger(buffer: ByteBuffer, at: Int, value: Int, bytes: Int) {
        for (i in 0 until bytes) buffer.put(at + i, (value shr (8 * i)).toByte())
    }
}
