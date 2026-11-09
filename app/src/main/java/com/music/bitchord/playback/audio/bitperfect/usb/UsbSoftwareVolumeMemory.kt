package com.music.bitchord.playback.audio.bitperfect.usb

/** Disk stores dB; a new attachment/process always reapplies the startup ceiling. */
internal class UsbSoftwareVolumeMemory(
    private val read: (String) -> Double?,
    private val persist: (String, Double) -> Unit,
) {
    private val session = HashMap<String, Double>()

    @Synchronized
    fun level(key: String): Double = session.getOrPut(key) {
        UsbSoftwareVolumePolicy.startupDb(read(key))
    }

    @Synchronized
    fun remember(key: String, db: Double): Double {
        val safe = UsbSoftwareVolumePolicy.normalizeDb(db)
        persist(key, safe)
        session[key] = safe
        return safe
    }

    @Synchronized
    fun reset(key: String? = null) {
        if (key == null) session.clear() else session.remove(key)
    }
}
