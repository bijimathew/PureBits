/*
 * Copyright (C) 2026 PureBits contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.music.bitchord.playback.audio.bitperfect.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class UsbSoftwareGainTest {
    private val halfDb = -6.020599913279624

    @Test fun sliderIsLinearInDecibelsAndZeroIsRealMute() {
        assertEquals(UsbSoftwareVolumePolicy.MUTE_DB, UsbSoftwareVolumePolicy.fractionToDb(0.0), 0.0)
        assertEquals(-40.0, UsbSoftwareVolumePolicy.fractionToDb(0.5), 0.0)
        assertEquals(-20.0, UsbSoftwareVolumePolicy.fractionToDb(0.75), 0.0)
        assertEquals(0.0, UsbSoftwareVolumePolicy.fractionToDb(1.0), 0.0)
        for (i in 1..100) {
            val fraction = i / 100.0
            assertEquals(fraction, UsbSoftwareVolumePolicy.dbToFraction(UsbSoftwareVolumePolicy.fractionToDb(fraction)), 1e-12)
        }
    }

    @Test fun decibelsConvertToAttenuationWithoutAmplification() {
        assertEquals(0.0, UsbSoftwareVolumePolicy.linearGain(UsbSoftwareVolumePolicy.MUTE_DB), 0.0)
        assertEquals(0.01, UsbSoftwareVolumePolicy.linearGain(-40.0), 1e-12)
        assertEquals(0.1, UsbSoftwareVolumePolicy.linearGain(-20.0), 1e-12)
        assertEquals(0.5, UsbSoftwareVolumePolicy.linearGain(halfDb), 1e-12)
        assertEquals(1.0, UsbSoftwareVolumePolicy.linearGain(0.0), 0.0)
    }

    @Test fun invalidOrAmplifyingGainRequestsMute() {
        for (db in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.01, 40.0, -80.0, -100.0)) {
            assertEquals(0.0, UsbSoftwareVolumePolicy.linearGain(db), 0.0)
            assertEquals(0.0, UsbSoftwareVolumePolicy.dbToFraction(db), 0.0)
        }
        for (fraction in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.01)) {
            assertEquals(UsbSoftwareVolumePolicy.MUTE_DB, UsbSoftwareVolumePolicy.fractionToDb(fraction), 0.0)
        }
    }

    @Test fun rememberedLoudVolumeNeverOverridesNewConnectionStartupCap() {
        assertEquals(-40.0, UsbSoftwareVolumePolicy.startupDb(null), 0.0)
        assertEquals(-40.0, UsbSoftwareVolumePolicy.startupDb(0.0), 0.0)
        assertEquals(-40.0, UsbSoftwareVolumePolicy.startupDb(-20.0), 0.0)
        assertEquals(-60.0, UsbSoftwareVolumePolicy.startupDb(-60.0), 0.0)
        assertEquals(UsbSoftwareVolumePolicy.MUTE_DB, UsbSoftwareVolumePolicy.startupDb(Double.NaN), 0.0)
        assertEquals(UsbSoftwareVolumePolicy.MUTE_DB, UsbSoftwareVolumePolicy.startupDb(UsbSoftwareVolumePolicy.MUTE_DB), 0.0)
    }

    @Test fun playerDuckingAddsAttenuationWithoutChangingUserLevel() {
        assertEquals(-46.020599913279625, UsbSoftwareVolumePolicy.combinedDb(-40.0, 0.5f), 1e-12)
        assertEquals(-60.0, UsbSoftwareVolumePolicy.combinedDb(-40.0, 0.1f), 1e-5)
        assertEquals(-32.123456789, UsbSoftwareVolumePolicy.combinedDb(-32.123456789, 1f), 0.0)
        assertEquals(0.0, UsbSoftwareVolumePolicy.combinedDb(0.0, 1f), 0.0)
    }

    @Test fun hardwareVolumeStillHonorsPlayerMuteAndFocusDuck() {
        assertEquals(0.0, UsbSoftwareVolumePolicy.combinedDb(null, 1f), 0.0)
        assertEquals(halfDb, UsbSoftwareVolumePolicy.combinedDb(null, 0.5f), 1e-12)
        assertEquals(UsbSoftwareVolumePolicy.MUTE_DB, UsbSoftwareVolumePolicy.combinedDb(null, 0f), 0.0)
    }

    @Test fun invalidPlayerVolumesMuteInHardwareAndSoftwareModes() {
        for (volume in listOf(0f, -0f, -0.1f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            for (userDb in listOf(null, -40.0, 0.0)) {
                val db = UsbSoftwareVolumePolicy.combinedDb(userDb, volume)
                assertEquals(UsbSoftwareVolumePolicy.MUTE_DB, db, 0.0)
                assertEquals(0.0, UsbSoftwareVolumePolicy.linearGain(db), 0.0)
            }
        }
    }

    @Test fun userMuteAndInvalidUserLevelCannotLeakThroughPlayerVolume() {
        for (user in listOf(UsbSoftwareVolumePolicy.MUTE_DB, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.0)) {
            for (player in listOf(0f, 0.5f, 1f)) {
                assertEquals(UsbSoftwareVolumePolicy.MUTE_DB, UsbSoftwareVolumePolicy.combinedDb(user, player), 0.0)
            }
        }
    }

    @Test fun combinedAttenuationOnlyStaysTheSameOrLowersGain() {
        for (user in listOf(null, 0.0, -20.0, -40.0, -79.0)) {
            val originalGain = UsbSoftwareVolumePolicy.linearGain(user ?: 0.0)
            for (i in 0..100) {
                val combinedGain = UsbSoftwareVolumePolicy.linearGain(UsbSoftwareVolumePolicy.combinedDb(user, i / 100f))
                assertTrue(combinedGain in 0.0..originalGain)
            }
        }
    }

    @Test fun combinedSubFloorAttenuationMutesRatherThanRoundingUp() {
        val combined = UsbSoftwareVolumePolicy.combinedDb(-79.0, 0.5f)
        assertEquals(UsbSoftwareVolumePolicy.MUTE_DB, combined, 0.0)
        assertEquals(0.0, UsbSoftwareVolumePolicy.linearGain(combined), 0.0)
    }

    @Test fun unityPreservesEveryIntegerByteIncludingAll32Bits() {
        for (format in listOf(UsbSoftwareGain.Format.PCM_16, UsbSoftwareGain.Format.PCM_24, UsbSoftwareGain.Format.PCM_32)) {
            val bytes = Random(1485).nextBytes(format.sampleBytes * 2 * 100)
            val buffer = ByteBuffer.wrap(bytes.copyOf())
            UsbSoftwareGain(44100, 2, format, initialDb = 0.0).processInPlace(buffer)
            assertArrayEquals(format.name, bytes, buffer.array())
        }
    }

    @Test fun unityPreservesValidFloatBytesAndSignedZero() {
        val buffer = floats(-1f, -0.5f, -0f, 0f, 0.125f, 1f)
        val before = buffer.array().copyOf()
        UsbSoftwareGain(44100, 2, UsbSoftwareGain.Format.FLOAT_32, initialDb = 0.0).processInPlace(buffer)
        assertArrayEquals(before, buffer.array())
    }

    @Test fun allFormatsProduceAllZeroBytesAtMute() {
        for (format in UsbSoftwareGain.Format.entries) {
            val bytes = Random(1).nextBytes(format.sampleBytes * 2 * 20)
            UsbSoftwareGain(48000, 2, format).processInPlace(ByteBuffer.wrap(bytes))
            assertArrayEquals(format.name, ByteArray(bytes.size), bytes)
        }
    }

    @Test fun scalesFullRange16BitIncludingNegativeExtreme() {
        val buffer = shorts(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt(), -20000, 20000)
        UsbSoftwareGain(44100, 2, UsbSoftwareGain.Format.PCM_16, initialDb = halfDb).processInPlace(buffer)
        assertEquals(-16384, buffer.short.toInt())
        assertEquals(16384, buffer.short.toInt())
        assertEquals(-10000, buffer.short.toInt())
        assertEquals(10000, buffer.short.toInt())
    }

    @Test fun scalesPacked24BitWithCorrectSignExtension() {
        val buffer = ByteBuffer.wrap(byteArrayOf(0, 0, -128, -1, -1, 127))
        UsbSoftwareGain(44100, 2, UsbSoftwareGain.Format.PCM_24, initialDb = halfDb).processInPlace(buffer)
        assertArrayEquals(byteArrayOf(0, 0, -64, 0, 0, 64), buffer.array())
    }

    @Test fun scalesFullRange32BitWithoutIntegerOverflow() {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(Int.MIN_VALUE).putInt(Int.MAX_VALUE)
        buffer.flip()
        UsbSoftwareGain(44100, 2, UsbSoftwareGain.Format.PCM_32, initialDb = halfDb).processInPlace(buffer)
        assertEquals(-1073741824, buffer.int)
        assertEquals(1073741824, buffer.int)
    }

    @Test fun floatSamplesAttenuateAndCorruptValuesCannotEscape() {
        val buffer = floats(-1f, 1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.5f)
        UsbSoftwareGain(44100, 2, UsbSoftwareGain.Format.FLOAT_32, initialDb = halfDb).processInPlace(buffer)
        assertEquals(-0.5f, buffer.float, 0f)
        assertEquals(0.5f, buffer.float, 0f)
        assertEquals(0f, buffer.float, 0f)
        assertEquals(0f, buffer.float, 0f)
        assertEquals(0f, buffer.float, 0f)
        assertEquals(0.25f, buffer.float, 0f)
    }

    @Test fun finiteOutOfRangeFloatsAreClippedInsteadOfOverflowing() {
        val buffer = floats(-8f, 8f)
        UsbSoftwareGain(44100, 2, UsbSoftwareGain.Format.FLOAT_32, initialDb = halfDb).processInPlace(buffer)
        assertEquals(-1f, buffer.float, 0f)
        assertEquals(1f, buffer.float, 0f)
    }

    @Test fun rampAdvancesOncePerStereoFrameAndFinishesAtExactUnity() {
        val buffer = repeatedStereo(Short.MAX_VALUE.toInt(), 10)
        val gain = UsbSoftwareGain(1000, 2, UsbSoftwareGain.Format.PCM_16, rampMs = 10)
        gain.setTargetDb(0.0)
        assertFalse(gain.atUnity)
        gain.processInPlace(buffer)
        var previous = 0
        repeat(10) {
            val left = buffer.short.toInt()
            val right = buffer.short.toInt()
            assertEquals(left, right)
            assertTrue(left >= previous)
            previous = left
        }
        assertEquals(Short.MAX_VALUE.toInt(), previous)
        assertEquals(1.0, gain.currentGain, 0.0)
        assertTrue(gain.atUnity)
    }

    @Test fun downwardRampsDoNotOvershootEitherEndpoint() {
        val buffer = repeatedStereo(Short.MAX_VALUE.toInt(), 10)
        val gain = UsbSoftwareGain(1000, 2, UsbSoftwareGain.Format.PCM_16, initialDb = 0.0)
        gain.setTargetDb(-20.0)
        gain.processInPlace(buffer)
        var previous = Short.MAX_VALUE.toInt()
        repeat(10) {
            val left = buffer.short.toInt()
            assertEquals(left, buffer.short.toInt())
            assertTrue(left <= previous)
            assertTrue(left >= 3277)
            previous = left
        }
        assertEquals(3277, previous)
        assertEquals(0.1, gain.currentGain, 0.0)
    }

    @Test fun muteAndInvalidRequestsTakeEffectBeforeNextSample() {
        for (db in listOf(UsbSoftwareVolumePolicy.MUTE_DB, Double.NaN, Double.POSITIVE_INFINITY, 1.0)) {
            val buffer = repeatedStereo(Short.MAX_VALUE.toInt(), 1)
            val gain = UsbSoftwareGain(44100, 2, UsbSoftwareGain.Format.PCM_16, initialDb = 0.0)
            gain.setTargetDb(db)
            gain.processInPlace(buffer)
            assertArrayEquals(ByteArray(4), buffer.array())
            assertEquals(0.0, gain.currentGain, 0.0)
        }
    }

    @Test fun conservativeInitialGainAppliesToVeryFirstFrame() {
        val buffer = repeatedStereo(Short.MAX_VALUE.toInt(), 1)
        val gain = UsbSoftwareGain(44100, 2, UsbSoftwareGain.Format.PCM_16, initialDb = UsbSoftwareVolumePolicy.startupDb(0.0))
        gain.processInPlace(buffer)
        assertEquals(328, buffer.short.toInt())
        assertEquals(328, buffer.short.toInt())
    }

    @Test fun processingHonorsRangeAndPreservesPositionLimitAndByteOrder() {
        val buffer = ByteBuffer.allocateDirect(16).order(ByteOrder.BIG_ENDIAN)
        repeat(16) { buffer.put(it, 0x7f.toByte()) }
        buffer.position(4).limit(12)
        UsbSoftwareGain(44100, 2, UsbSoftwareGain.Format.PCM_16).processInPlace(buffer)
        assertEquals(4, buffer.position())
        assertEquals(12, buffer.limit())
        assertEquals(ByteOrder.BIG_ENDIAN, buffer.order())
        repeat(4) { assertEquals(0x7f.toByte(), buffer.get(it)) }
        for (i in 4 until 12) assertEquals(0.toByte(), buffer.get(i))
        buffer.limit(16)
        for (i in 12 until 16) assertEquals(0x7f.toByte(), buffer.get(i))
    }

    @Test fun splittingBuffersPreservesTheSameRampAndSamples() {
        val all = repeatedStereo(12345, 20)
        val split = repeatedStereo(12345, 20)
        val a = UsbSoftwareGain(1000, 2, UsbSoftwareGain.Format.PCM_16)
        val b = UsbSoftwareGain(1000, 2, UsbSoftwareGain.Format.PCM_16)
        a.setTargetDb(-20.0)
        b.setTargetDb(-20.0)
        a.processInPlace(all)
        b.processInPlace(split, 0, 3 * 4)
        b.processInPlace(split, 3 * 4, 4 * 4)
        b.processInPlace(split, 7 * 4, 13 * 4)
        assertArrayEquals(all.array(), split.array())
    }

    @Test fun repeatedSameTargetDoesNotRestartRamp() {
        val gain = UsbSoftwareGain(1000, 2, UsbSoftwareGain.Format.PCM_16)
        repeat(10) {
            val frame = repeatedStereo(12345, 1)
            gain.setTargetDb(-20.0)
            gain.processInPlace(frame)
        }
        assertEquals(0.1, gain.currentGain, 0.0)
    }

    @Test fun rapidControlUpdatesStayFiniteAndMatchBothChannels() {
        val gain = UsbSoftwareGain(48000, 2, UsbSoftwareGain.Format.PCM_32, initialDb = -40.0)
        repeat(500) { i ->
            gain.setTargetDb(if (i % 7 == 0) Double.NaN else -(i % 80).toDouble())
            val frame = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(Int.MIN_VALUE).putInt(Int.MIN_VALUE)
            frame.flip()
            gain.processInPlace(frame)
            val left = frame.int
            assertEquals(left, frame.int)
            assertTrue(left <= 0)
            assertTrue(gain.currentGain.isFinite())
            assertTrue(gain.currentGain in 0.0..1.0)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPartialFramesRatherThanTransmittingUnattenuatedTail() {
        UsbSoftwareGain(44100, 2, UsbSoftwareGain.Format.PCM_24).processInPlace(ByteBuffer.allocate(7))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsReadonlyBuffersEvenAtUnity() {
        UsbSoftwareGain(44100, 2, UsbSoftwareGain.Format.PCM_16, initialDb = 0.0)
            .processInPlace(ByteBuffer.allocate(4).asReadOnlyBuffer())
    }

    @Test fun noRampModeAppliesRequestedGainImmediately() {
        val gain = UsbSoftwareGain(44100, 2, UsbSoftwareGain.Format.PCM_16, initialDb = -40.0, rampMs = 0)
        gain.setTargetDb(halfDb)
        val buffer = shorts(-20000, 20000)
        gain.processInPlace(buffer)
        assertEquals(-10000, buffer.short.toInt())
        assertEquals(10000, buffer.short.toInt())
    }

    private fun floats(vararg samples: Float): ByteBuffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
        samples.forEach(::putFloat)
        flip()
    }

    private fun shorts(vararg samples: Int): ByteBuffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        samples.forEach { putShort(it.toShort()) }
        flip()
    }

    private fun repeatedStereo(sample: Int, frames: Int): ByteBuffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
        repeat(frames * 2) { putShort(sample.toShort()) }
        flip()
    }
}
