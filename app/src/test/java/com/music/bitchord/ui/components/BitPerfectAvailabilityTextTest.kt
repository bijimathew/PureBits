package com.music.bitchord.ui.components

import com.music.bitchord.R
import com.music.bitchord.data.settings.BitPerfectMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BitPerfectAvailabilityTextTest {
    @Test
    fun nativeUsbSupportAndQueryFailuresExplainTheOptionalAndroidFeature() {
        for (reason in listOf("native_usb_unavailable", "native_usb_query_failed")) {
            assertEquals(R.string.bit_perfect_unavailable_native_usb, bitPerfectReasonResource(BitPerfectMode.USB_ANDROID, reason))
        }
    }

    @Test
    fun builtinRequiresWiredOutputAndDoesNotSuggestUsingTheUsbIem() {
        assertEquals(R.string.bit_perfect_unavailable_no_wired, bitPerfectReasonResource(BitPerfectMode.DAP_EXCLUSIVE, "no_wired_output"))
    }

    @Test
    fun apiRequirementsBelongToTheRequestedOutput() {
        assertEquals(R.string.bit_perfect_requires_android_14, bitPerfectReasonResource(BitPerfectMode.USB_ANDROID, "android_version"))
        assertEquals(R.string.bit_perfect_requires_android_8_1, bitPerfectReasonResource(BitPerfectMode.DAP_EXCLUSIVE, "android_version"))
        assertNull(bitPerfectReasonResource(BitPerfectMode.USB_DIRECT, "android_version"))
    }

    @Test
    fun missingUsbPermissionAndTrackFormatAreDifferentFailures() {
        assertEquals(R.string.bit_perfect_unavailable_no_usb, bitPerfectReasonResource(BitPerfectMode.USB_DIRECT, "no_usb"))
        assertEquals(R.string.bit_perfect_unavailable_permission, bitPerfectReasonResource(BitPerfectMode.USB_DIRECT, "permission_denied"))
        assertEquals(R.string.bit_perfect_unavailable_format, bitPerfectReasonResource(BitPerfectMode.USB_ANDROID, "unsupported"))
        assertEquals(R.string.bit_perfect_unavailable_exclusive, bitPerfectReasonResource(BitPerfectMode.DAP_EXCLUSIVE, "exclusive_unavailable"))
    }

    @Test
    fun directSafetyStopsAndUnknownDiagnosticsNeverBecomeGenericWaiting() {
        assertEquals(R.string.usb_direct_stopped, bitPerfectReasonResource(BitPerfectMode.USB_DIRECT, "direct_usb_stopped"))
        assertNull(bitPerfectReasonResource(BitPerfectMode.USB_DIRECT, "USB transfer stopped: -4"))
    }
}
