package com.music.bitchord.playback.audio.bitperfect

import com.music.bitchord.data.settings.BitPerfectMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BitPerfectAvailabilityTest {
    @Test fun usbEarphonesDoNotMakeTheBuiltInWiredDacEligible() {
        val reasons = bitPerfectAvailability(36, true, true, true, false, false)
        assertFalse(reasons.containsKey(BitPerfectMode.USB_DIRECT))
        assertEquals("no_wired_output", reasons[BitPerfectMode.DAP_EXCLUSIVE])
        assertEquals("native_usb_unavailable", reasons[BitPerfectMode.USB_ANDROID])
    }

    @Test fun recentAndroidAloneDoesNotProveNativeBitPerfectSupport() {
        val reasons = bitPerfectAvailability(36, true, true, true, true, false)
        assertEquals("native_usb_unavailable", reasons[BitPerfectMode.USB_ANDROID])
        assertFalse(reasons.containsKey(BitPerfectMode.DAP_EXCLUSIVE))
    }

    @Test fun aFailedPlatformQueryIsExplainedRatherThanClaimedSupported() {
        val reasons = bitPerfectAvailability(36, true, true, true, false, null)
        assertEquals("native_usb_query_failed", reasons[BitPerfectMode.USB_ANDROID])
    }

    @Test fun nativeBitPerfectRequiresAndroid14EvenWhenDeviceFactsSaySupported() {
        val reasons = bitPerfectAvailability(33, true, true, true, true, true)
        assertEquals("android_version", reasons[BitPerfectMode.USB_ANDROID])
        assertFalse(reasons.containsKey(BitPerfectMode.USB_DIRECT))
    }

    @Test fun directPermissionAndAndroidRoutingAreIndependent() {
        val reasons = bitPerfectAvailability(36, true, false, false, false, null)
        assertEquals("permission_denied", reasons[BitPerfectMode.USB_DIRECT])
        assertEquals("no_usb", reasons[BitPerfectMode.USB_ANDROID])
    }

    @Test fun supportedDevicesStillNeedFormatAndExclusiveOpenValidation() {
        val reasons = bitPerfectAvailability(36, true, true, true, true, true)
        assertEquals(emptyMap<BitPerfectMode, String>(), reasons)
    }
}
