package com.music.bitchord.playback.audio.bitperfect.usb

import com.music.bitchord.data.settings.UsbDirectVolumeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class UsbDirectVolumeSelectionTest {
    @Test fun autoAndSoftwareNeverIssueHardwareVolumeCommands() {
        for (mode in listOf(UsbDirectVolumeMode.AUTO, UsbDirectVolumeMode.SOFTWARE)) {
            val result = UsbDirectVolumeSelection.select<String>(mode, -40.0) {
                error("software selection must not touch hardware volume")
            }
            assertNull(result.hardware)
            assertEquals(-40.0, result.softwareDb!!, 0.0)
        }
    }

    @Test fun successfulExplicitHardwareInitializationKeepsHardwareSupport() {
        val hardware = Any()
        var calls = 0
        val result = UsbDirectVolumeSelection.select(UsbDirectVolumeMode.HARDWARE, -40.0) {
            calls++
            hardware
        }
        assertEquals(1, calls)
        assertSame(hardware, result.hardware)
        assertNull(result.softwareDb)
    }

    @Test fun missingHardwareControlFallsBackToAttenuation() {
        val result = UsbDirectVolumeSelection.select<Any>(UsbDirectVolumeMode.HARDWARE, -60.0) { null }
        assertNull(result.hardware)
        assertEquals(-60.0, result.softwareDb!!, 0.0)
    }

    @Test fun failedHardwareWriteOrReadbackCannotSelectFixedFullScalePlayback() {
        for (failure in listOf(IllegalStateException("write failed"), IllegalArgumentException("readback mismatch"))) {
            val result = UsbDirectVolumeSelection.select<Any>(UsbDirectVolumeMode.HARDWARE, -40.0) { throw failure }
            assertNull(result.hardware)
            assertEquals(-40.0, result.softwareDb!!, 0.0)
        }
    }

    @Test fun invalidSoftwareFallbackGainMutes() {
        for (mode in UsbDirectVolumeMode.entries) {
            for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, 1.0)) {
                val result = UsbDirectVolumeSelection.select<Any>(mode, invalid) { null }
                assertNull(result.hardware)
                assertEquals(UsbSoftwareVolumePolicy.MUTE_DB, result.softwareDb!!, 0.0)
            }
        }
    }

    @Test fun virtualMachineErrorsAreNotSwallowedAsHardwareCompatibilityFailures() {
        val failure = OutOfMemoryError("simulated fatal VM failure")
        val actual = assertThrows(OutOfMemoryError::class.java) {
            UsbDirectVolumeSelection.select<Any>(UsbDirectVolumeMode.HARDWARE, -40.0) { throw failure }
        }
        assertSame(failure, actual)
    }
}
