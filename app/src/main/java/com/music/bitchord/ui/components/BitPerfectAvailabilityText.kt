package com.music.bitchord.ui.components

import androidx.annotation.StringRes
import com.music.bitchord.R
import com.music.bitchord.data.settings.BitPerfectMode

/** Engine reason codes stay independent of UI resources and distinguish separate output APIs. */
@StringRes
internal fun bitPerfectReasonResource(mode: BitPerfectMode, reason: String): Int? = when (reason) {
    "android_version" -> when (mode) {
        BitPerfectMode.USB_ANDROID -> R.string.bit_perfect_requires_android_14
        BitPerfectMode.DAP_EXCLUSIVE -> R.string.bit_perfect_requires_android_8_1
        else -> null
    }
    "no_usb" -> R.string.bit_perfect_unavailable_no_usb
    "no_wired_output" -> R.string.bit_perfect_unavailable_no_wired
    "permission_denied" -> R.string.bit_perfect_unavailable_permission
    "native_usb_unavailable", "native_usb_query_failed" -> R.string.bit_perfect_unavailable_native_usb
    "unsupported", "format" -> R.string.bit_perfect_unavailable_format
    "exclusive_unavailable" -> R.string.bit_perfect_unavailable_exclusive
    "direct_usb_stopped" -> R.string.usb_direct_stopped
    else -> null // Preserve a useful transport diagnostic rather than relabel it as generic waiting.
}
