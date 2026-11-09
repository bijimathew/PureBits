package com.music.bitchord.playback.audio.bitperfect

import com.music.bitchord.playback.audio.bitperfect.usb.UsbVolume
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DacVolumeTest {

    private fun snapshot(current: Double, min: Double = -127.0, max: Double = 0.0) =
        UsbVolume.Snapshot(available = true, minDb = min, maxDb = max, currentDb = current, restoreLimitDb = current)

    @After
    fun clear() {
        DacVolume.onSnapshot(null)
        DacVolume.resetSoftwareSession()
    }

    @Test
    fun travelStartsAtMinus80SoTheLowerHalfIsAudible() {
        val volume = snapshot(-40.0)
        assertEquals(0.5f, DacVolume.fractionOf(volume), 1e-6f)
        assertEquals(-40.0, DacVolume.dbOf(volume, 0.5f), 1e-9)
        assertEquals(0f, DacVolume.fractionOf(snapshot(-100.0)), 0f)
        // Only the very bottom is the DAC's own minimum.
        assertEquals(-127.0, DacVolume.dbOf(volume, 0f), 0.0)
    }

    @Test
    fun narrowRangesUseTheirOwnMinimum() {
        val volume = snapshot(-30.0, min = -60.0)
        assertEquals(0.5f, DacVolume.fractionOf(volume), 1e-6f)
        assertEquals(-60.0, DacVolume.dbOf(volume, 0f), 0.0)
    }

    @Test
    fun stepsInSixteenthsAndReportsTheEnds() {
        DacVolume.onSnapshot(snapshot(0.0))
        assertEquals(1f, DacVolume.level.value!!, 0f)
        assertFalse("already at the top", DacVolume.step(+1))
        assertTrue(DacVolume.step(-1))
        assertEquals(15f / 16f, DacVolume.level.value!!, 1e-6f)
    }

    @Test
    fun noDacMeansNoLevel() {
        DacVolume.onSnapshot(UsbVolume.Snapshot(available = false))
        assertNull(DacVolume.level.value)
        assertFalse(DacVolume.step(+1))
    }

    @Test
    fun softwareSnapshotUsesActualGainAndKeysMoveOneDb() {
        DacVolume.onSoftwareVolume(-40.0)
        assertEquals(0.5f, DacVolume.level.value!!, 0f)
        assertTrue(DacVolume.step(1))
        assertEquals(41f / 80f, DacVolume.level.value!!, 1e-6f)
    }

    @Test
    fun highSoftwareVolumeWaitsForForegroundConfirmation() {
        DacVolume.onSoftwareVolume(-40.0)
        DacVolume.set(1f)
        assertEquals(0.75f, DacVolume.level.value!!, 0f)
        assertTrue(DacVolume.highVolumeRequested.value)
        DacVolume.confirmHighVolume()
        assertEquals(1f, DacVolume.level.value!!, 0f)
        assertFalse(DacVolume.highVolumeRequested.value)
    }

    @Test
    fun repeatedVolumeUpCannotBypassConfirmation() {
        DacVolume.onSoftwareVolume(-20.0)
        repeat(200) { assertFalse(DacVolume.step(1)) }
        assertEquals(0.75f, DacVolume.level.value!!, 0f)
        DacVolume.dismissHighVolume()
        assertEquals(0.75f, DacVolume.level.value!!, 0f)
    }

    @Test
    fun invalidSoftwareLevelMutesRatherThanRaisingGain() {
        DacVolume.onSoftwareVolume(-40.0)
        DacVolume.set(Float.NaN)
        assertEquals(0f, DacVolume.level.value!!, 0f)
        DacVolume.set(Float.POSITIVE_INFINITY)
        assertEquals(0f, DacVolume.level.value!!, 0f)
    }

    @Test
    fun laterDownRequestCancelsPendingHighVolume() {
        DacVolume.onSoftwareVolume(-40.0)
        DacVolume.set(1f)
        DacVolume.set(0.5f)
        DacVolume.confirmHighVolume()
        assertEquals(0.5f, DacVolume.level.value!!, 0f)
        assertFalse(DacVolume.highVolumeRequested.value)
    }

    @Test
    fun softwareSessionResetRequiresNewHighOutputConsent() {
        DacVolume.onSoftwareVolume(-40.0)
        DacVolume.set(1f)
        DacVolume.confirmHighVolume()
        DacVolume.onSoftwareVolume(null)
        DacVolume.resetSoftwareSession()
        DacVolume.onSoftwareVolume(-40.0)
        DacVolume.set(1f)
        assertEquals(0.75f, DacVolume.level.value!!, 0f)
        assertTrue(DacVolume.highVolumeRequested.value)
    }

    @Test
    fun trackReopenRetainsConsentButClearsPendingDialogWhenInactive() {
        DacVolume.onSoftwareVolume(-40.0)
        DacVolume.set(1f)
        DacVolume.confirmHighVolume()
        DacVolume.onSoftwareVolume(null)
        assertNull(DacVolume.level.value)
        assertFalse(DacVolume.highVolumeRequested.value)
        DacVolume.onSoftwareVolume(-40.0)
        DacVolume.set(1f)
        assertEquals(1f, DacVolume.level.value!!, 0f)
        assertFalse(DacVolume.highVolumeRequested.value)
    }
}
