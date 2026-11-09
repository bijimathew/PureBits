/*
 * Copyright (C) 2026 PureBits contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.music.bitchord.playback.audio.bitperfect.usb

import kotlin.math.pow
import kotlin.math.log10

/**
 * Digital attenuation policy, independent of Android's mixer and a DAC's volume controls.
 * These are digital gain limits, not acoustic hearing-safety guarantees.
 */
internal object UsbSoftwareVolumePolicy {
    const val FLOOR_DB = -80.0
    const val MUTE_DB = -120.0
    const val STARTUP_DB = -40.0
    const val HIGH_LEVEL_DB = -20.0

    /**
     * Invalid or amplifying requests fail closed. Levels at/below the slider floor mute instead
     * of being rounded up to a louder gain. A mute is represented by a finite sentinel.
     */
    fun normalizeDb(db: Double): Double = when {
        !db.isFinite() || db > 0.0 -> MUTE_DB
        db <= FLOOR_DB -> MUTE_DB
        else -> db
    }

    /** Zero really is silence; no tiny residual gain is sent at the mute sentinel. */
    fun linearGain(db: Double): Double {
        val normalized = normalizeDb(db)
        return if (normalized <= MUTE_DB) 0.0 else 10.0.pow(normalized / 20.0)
    }

    /**
     * The slider is linear in dB from -80 to 0, with its zero position reserved for mute.
     * A 50% position means -40 dB, not half of an unknown device's acoustic loudness.
     */
    fun fractionToDb(fraction: Double): Double = when {
        !fraction.isFinite() || fraction <= 0.0 || fraction > 1.0 -> MUTE_DB
        else -> FLOOR_DB * (1.0 - fraction)
    }

    fun dbToFraction(db: Double): Double {
        val normalized = normalizeDb(db)
        return if (normalized <= FLOOR_DB) 0.0 else (1.0 - normalized / FLOOR_DB).coerceIn(0.0, 1.0)
    }

    /** A new USB connection or process must not restore a remembered loud setting unchecked. */
    fun startupDb(rememberedDb: Double?): Double =
        minOf(normalizeDb(rememberedDb ?: STARTUP_DB), STARTUP_DB)

    /**
     * Keeps user-selected attenuation separate from temporary player mute/focus ducking.
     * A null user level means hardware volume supplies the ordinary listening level; the
     * player's additional digital attenuation still applies. Neither input may amplify.
     */
    fun combinedDb(userDb: Double?, playerVolume: Float): Double {
        if (!playerVolume.isFinite() || playerVolume <= 0f || playerVolume > 1f) return MUTE_DB
        val user = normalizeDb(userDb ?: 0.0)
        if (user == MUTE_DB || playerVolume == 1f) return user
        return normalizeDb(user + 20.0 * log10(playerVolume.toDouble()))
    }
}
