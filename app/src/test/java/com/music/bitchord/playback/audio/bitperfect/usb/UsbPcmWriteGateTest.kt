package com.music.bitchord.playback.audio.bitperfect.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbPcmWriteGateTest {
    private val halfDb = -6.020599913279624

    @Test fun partialAndZeroNativeWritesNeverDoubleAttenuateOrConsumeRawSamplesEarly() {
        val source = stereo(20000, 4)
        val original = source.array().copyOf()
        val sent = ByteArrayOutputStream()
        val writes = ArrayDeque(listOf(4, 0, 4, 8))
        val gain = UsbSoftwareGain(1000, 2, UsbSoftwareGain.Format.PCM_16, initialDb = halfDb, rampMs = 0)
        val gate = UsbPcmWriteGate(2, 2, 1000, gain) { buffer, offset, count ->
            val taken = writes.removeFirst()
            check(taken <= count)
            for (i in 0 until taken) sent.write(buffer.get(offset + i).toInt())
            taken
        }
        assertEquals(4, gate.write(source))
        assertEquals(4, source.position())
        assertEquals(0, gate.write(source))
        assertEquals(4, source.position())
        assertEquals(4, gate.write(source))
        assertEquals(8, gate.write(source))
        assertEquals(source.limit(), source.position())
        assertArrayEquals(original, source.array())
        assertArrayEquals(stereo(10000, 4).array(), sent.toByteArray())
    }

    @Test fun nativeOnlySeesAttenuatedFirstFrameAndRawInputIsNeverModified() {
        val source = stereo(32767, 3)
        val gain = UsbSoftwareGain(1000, 2, UsbSoftwareGain.Format.PCM_16, initialDb = -40.0)
        var calls = 0
        val gate = UsbPcmWriteGate(2, 2, 1000, gain) { buffer, offset, count ->
            calls++
            assertEquals(328, buffer.order(ByteOrder.LITTLE_ENDIAN).getShort(offset).toInt())
            assertEquals(328, buffer.getShort(offset + 2).toInt())
            count
        }
        assertEquals(12, gate.write(source))
        assertEquals(1, calls)
        assertArrayEquals(stereo(32767, 3).array(), source.array())
    }

    @Test fun stagedTailIsProcessedOnceEvenIfControlChangesDuringNativeBackpressure() {
        val source = stereo(20000, 2)
        val gain = UsbSoftwareGain(1000, 2, UsbSoftwareGain.Format.PCM_16, initialDb = halfDb, rampMs = 0)
        val sent = ByteArrayOutputStream()
        val gate = UsbPcmWriteGate(2, 2, 1000, gain) { buffer, offset, _ ->
            repeat(4) { sent.write(buffer.get(offset + it).toInt()) }
            4
        }
        gate.write(source)
        gain.setTargetDb(-20.0)
        gate.write(source)
        // An already scaled tail must not get multiplied a second time on a retry.
        assertArrayEquals(stereo(10000, 2).array(), sent.toByteArray())
        val next = stereo(20000, 1)
        gate.write(next)
        assertArrayEquals(stereo(10000, 2).array() + stereo(2000, 1).array(), sent.toByteArray())
    }

    @Test fun eachNewChunkUsesCurrentGainAndNoMoreThanTenMillisecondsIsStaged() {
        val source = stereo(20000, 25)
        val gain = UsbSoftwareGain(1000, 2, UsbSoftwareGain.Format.PCM_16, initialDb = halfDb, rampMs = 0)
        var calls = 0
        val sent = ByteArrayOutputStream()
        val gate = UsbPcmWriteGate(2, 2, 1000, gain) { buffer, offset, count ->
            calls++
            org.junit.Assert.assertTrue(count <= 10 * 4)
            repeat(count) { sent.write(buffer.get(offset + it).toInt()) }
            count
        }
        assertEquals(40, gate.write(source))
        gain.setTargetDb(-20.0)
        assertEquals(40, gate.write(source))
        assertEquals(20, gate.write(source))
        assertEquals(3, calls)
        assertArrayEquals(stereo(10000, 10).array() + stereo(2000, 15).array(), sent.toByteArray())
    }

    @Test fun changedSourceCannotTransmitPendingPcm() {
        val source = stereo(10000, 2)
        var calls = 0
        val gate = gate { _, _, _ -> calls++; 0 }
        gate.write(source)
        assertThrows(IllegalStateException::class.java) { gate.write(stereo(10000, 2)) }
        assertEquals(1, calls)
    }

    @Test fun externallyChangedSourceCursorCannotTransmitPendingPcm() {
        val source = stereo(10000, 2)
        var calls = 0
        val gate = gate { _, _, _ -> calls++; 0 }
        gate.write(source)
        source.position(4)
        assertThrows(IllegalStateException::class.java) { gate.write(source) }
        assertEquals(1, calls)
    }

    @Test fun shortenedSourceCannotTransmitPendingPcmBeyondItsCurrentLimit() {
        val source = stereo(10000, 2)
        var calls = 0
        val gate = gate { _, _, _ -> calls++; 0 }
        gate.write(source)
        source.limit(4)
        assertThrows(IllegalStateException::class.java) { gate.write(source) }
        assertEquals(1, calls)
    }

    @Test fun incompleteFramesFailBeforeAnyNativeSamplesAreSent() {
        var calls = 0
        val gate = gate { _, _, count -> calls++; count }
        assertThrows(IllegalStateException::class.java) { gate.write(ByteBuffer.allocate(7)) }
        assertEquals(0, calls)
    }

    @Test fun invalidFormatCannotCreateAnUnprotectedOrMisalignedGate() {
        val gain = UsbSoftwareGain(1000, 2, UsbSoftwareGain.Format.PCM_16)
        for (format in listOf(Triple(0, 2, 1000), Triple(2, 0, 1000), Triple(2, 2, 0))) {
            assertThrows(IllegalArgumentException::class.java) {
                UsbPcmWriteGate(format.first, format.second, format.third, gain) { _, _, count -> count }
            }
        }
    }

    @Test fun flushDropsPendingProcessedAudioAndAllowsAnotherSourceWithoutResettingGain() {
        val gain = UsbSoftwareGain(1000, 2, UsbSoftwareGain.Format.PCM_16, initialDb = halfDb, rampMs = 0)
        var accepted = false
        val sent = ByteArrayOutputStream()
        val gate = UsbPcmWriteGate(2, 2, 1000, gain) { buffer, offset, count ->
            if (accepted) {
                repeat(count) { sent.write(buffer.get(offset + it).toInt()) }
                count
            } else 0
        }
        val previous = stereo(30000, 2)
        gate.write(previous)
        gate.flush()
        accepted = true
        gate.write(stereo(-20000, 2))
        assertArrayEquals(stereo(-10000, 2).array(), sent.toByteArray())
        assertEquals(halfDb, gain.targetDb, 0.0)
        assertEquals(0, previous.position())
    }

    @Test fun muteAndInvalidGainRequestsCannotSendFullScaleInput() {
        for (db in listOf(UsbSoftwareVolumePolicy.MUTE_DB, Double.NaN, Double.POSITIVE_INFINITY, 1.0)) {
            val gain = UsbSoftwareGain(1000, 2, UsbSoftwareGain.Format.PCM_16, initialDb = db)
            val gate = UsbPcmWriteGate(2, 2, 1000, gain) { buffer, offset, count ->
                repeat(count) { assertEquals(0.toByte(), buffer.get(offset + it)) }
                count
            }
            val source = stereo(32767, 3)
            assertEquals(12, gate.write(source))
        }
    }

    @Test fun acceptsOnlyWholeFramesReturnedByNative() {
        for (badCount in listOf(-4, 1, 100)) {
            val source = stereo(10000, 1)
            val gate = gate { _, _, _ -> badCount }
            assertThrows(IllegalStateException::class.java) { gate.write(source) }
            assertEquals(0, source.position())
        }
    }

    @Test fun aThrownNativeTransferErrorDoesNotAdvanceRawInputOrBypassGainOnRetry() {
        var fail = true
        val source = stereo(32767, 1)
        val gate = gate { buffer, offset, count ->
            if (fail) throw IllegalStateException("simulated USB transfer loss")
            assertEquals(328, buffer.order(ByteOrder.LITTLE_ENDIAN).getShort(offset).toInt())
            count
        }
        assertThrows(IllegalStateException::class.java) { gate.write(source) }
        assertEquals(0, source.position())
        fail = false
        assertEquals(4, gate.write(source))
    }

    @Test fun emptyBufferCannotTriggerNativeOutput() {
        val gate = gate { _, _, _ -> error("native must not receive empty input") }
        assertEquals(0, gate.write(ByteBuffer.allocate(0)))
    }

    private fun gate(send: (ByteBuffer, Int, Int) -> Int) = UsbPcmWriteGate(
        2, 2, 1000,
        UsbSoftwareGain(1000, 2, UsbSoftwareGain.Format.PCM_16, initialDb = -40.0),
        send,
    )

    private fun stereo(sample: Int, frames: Int): ByteBuffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
        repeat(frames * 2) { putShort(sample.toShort()) }
        flip()
    }
}
