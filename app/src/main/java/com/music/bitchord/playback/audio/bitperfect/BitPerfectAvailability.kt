package com.music.bitchord.playback.audio.bitperfect

import com.music.bitchord.data.settings.BitPerfectMode

/** Device/API eligibility only; a track's format and an exclusive-open grant are checked later. */
internal fun bitPerfectAvailability(
    apiLevel: Int,
    directUsbAttached: Boolean,
    usbPermission: Boolean,
    androidUsbAttached: Boolean,
    wiredAnalogAttached: Boolean,
    nativeUsbSupported: Boolean?,
): Map<BitPerfectMode, String> = buildMap {
    when {
        !directUsbAttached -> put(BitPerfectMode.USB_DIRECT, "no_usb")
        !usbPermission -> put(BitPerfectMode.USB_DIRECT, "permission_denied")
    }
    when {
        apiLevel < 27 -> put(BitPerfectMode.DAP_EXCLUSIVE, "android_version")
        !wiredAnalogAttached -> put(BitPerfectMode.DAP_EXCLUSIVE, "no_wired_output")
    }
    when {
        apiLevel < 34 -> put(BitPerfectMode.USB_ANDROID, "android_version")
        !androidUsbAttached -> put(BitPerfectMode.USB_ANDROID, "no_usb")
        nativeUsbSupported == null -> put(BitPerfectMode.USB_ANDROID, "native_usb_query_failed")
        !nativeUsbSupported -> put(BitPerfectMode.USB_ANDROID, "native_usb_unavailable")
    }
}
