/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.music.bitchord.playback.audio.bitperfect

import java.nio.ByteBuffer

/**
 * One exclusive, mixer-free output opened at a track's own rate and channel
 * count, or at the closest the DAC offers when it cannot take the track's own;
 * [sampleRate] and [validBits] say which. [BitPerfectAudioSink] owns exactly
 * one at a time and drives it from the playback thread. Direct USB can apply
 * software gain without resampling or involving Android's mixer.
 *
 * Every call except [release] may throw [BitPerfectTransportException] once
 * the route is lost. The sink turns that into a recoverable Media3 error, so
 * the player rebuilds its output rather than carrying on into a device that
 * is no longer the one this stream was verified against.
 */
internal interface BitPerfectTransport {
    /** Which of the three output paths this is, for status and logs. */
    val driver: BitPerfectDriver
    val deviceName: String
    val sampleRate: Int
    val channelCount: Int

    /** Integer precision on the wire: 16, 24 or 32. */
    val outputBits: Int

    /** How many of [outputBits] the DAC actually resolves: 24 of a 32-bit USB slot, say. */
    val validBits: Int get() = outputBits

    /** How much audio the transport itself buffers, in frames. */
    val bufferSizeFrames: Long

    /** Non-null only for exclusive USB with software volume (dB, including a true mute sentinel). */
    val softwareVolumeDb: Double? get() = null
    val softwareAttenuationDb: Double? get() = softwareVolumeDb
    val samplesUnchanged: Boolean get() = true

    /** Separate from the user's DAC setting: Media3 mute and audio-focus ducking. */
    fun setPlayerVolume(volume: Float) = Unit

    /** Copies as much of [buffer] as fits without blocking; returns the byte count taken. */
    fun write(buffer: ByteBuffer): Int

    fun play()

    fun pause()

    /** Drops buffered audio and resets [playedFrames] to zero. */
    fun flush()

    /** No more audio follows: play out what is buffered. */
    fun stop()

    /** Frames the device has actually played since the last [flush], excluding inserted silence. */
    fun playedFrames(): Long

    fun release()
}

/**
 * [releasesWhenIdle]: whether a long pause should hand the device back. The
 * mixer path is an AudioTrack, which Android puts into standby by itself; the
 * other two hold the DAC, and the USB one a worker thread, for as long as they
 * stay open.
 */
internal enum class BitPerfectDriver(val label: String, val releasesWhenIdle: Boolean) {
    ANDROID_MIXER("Android bit-perfect mixer", releasesWhenIdle = true),
    USB_DIRECT("Direct USB", releasesWhenIdle = true),
    AAUDIO_EXCLUSIVE("AAudio exclusive", releasesWhenIdle = true),
}

internal class BitPerfectTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Marks output safety failures so stream recovery cannot retry them as addon failures. */
internal class DirectUsbSafetyException(message: String, cause: Throwable? = null) : Exception(message, cause)
