package com.music.bitchord.playback.audio.bitperfect.usb

import com.music.bitchord.data.settings.UsbDirectVolumeMode

/** Hardware control is explicitly opt-in; failed initialization always retains PCM attenuation. */
internal object UsbDirectVolumeSelection {
    data class Result<T>(val hardware: T?, val softwareDb: Double?)

    fun <T> select(
        mode: UsbDirectVolumeMode,
        softwareDb: Double,
        openHardware: () -> T?,
    ): Result<T> {
        val safeDb = UsbSoftwareVolumePolicy.normalizeDb(softwareDb)
        if (mode != UsbDirectVolumeMode.HARDWARE) return Result(null, safeDb)
        val hardware = try {
            openHardware()
        } catch (_: Exception) {
            null
        }
        return if (hardware != null) Result(hardware, null) else Result(null, safeDb)
    }
}
