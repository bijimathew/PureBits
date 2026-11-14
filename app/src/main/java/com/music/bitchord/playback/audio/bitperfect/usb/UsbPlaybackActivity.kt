package com.music.bitchord.playback.audio.bitperfect.usb

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.music.bitchord.data.TrackLog

/**
 * Xiaomi background-audio compatibility for the raw USB driver.
 *
 * USB PCM never enters AudioTrack, so a vendor's active-audio tracking can miss
 * our playback despite the media foreground service and CPU wake lock. A static
 * loop of digital silence makes the playback visible to Android. It contains no
 * song data, prefers only the built-in speaker, and never requests focus or
 * changes any system/USB volume. The music remains on the exclusive USB path.
 *
 * Lifetime follows the transport, including focus-driven pause, flush, failure
 * and detach. No timer or writer thread is needed. See the background report
 * for the observed process stalls and the limits of this vendor workaround.
 */
internal class UsbPlaybackActivity private constructor(private val manager: AudioManager) : AutoCloseable {
    private var track: AudioTrack? = null
    private var closed = false
    @Volatile var status: String = "idle"
        private set

    private val routeListener = AudioRouting.OnRoutingChangedListener { routing ->
        checkRoute(routing)
    }

    @Synchronized
    fun start() {
        if (closed || track != null) return
        var created: AudioTrack? = null
        try {
            val speaker = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: error("No built-in speaker for USB playback activity")
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .apply {
                    if (Build.VERSION.SDK_INT >= 29) setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                }.build()
            val silence = ByteArray(LOOP_FRAMES * 2) // Mono PCM16: all zero, no input from the song.
            val output = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(48_000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(silence.size)
                .build()
            created = output
            check(output.setPreferredDevice(speaker)) { "Speaker preference rejected" }
            check(output.write(silence, 0, silence.size) == silence.size) { "Silence buffer rejected" }
            check(output.state == AudioTrack.STATE_INITIALIZED) { "Activity track not initialized" }
            check(output.setLoopPoints(0, LOOP_FRAMES, -1) == AudioTrack.SUCCESS) { "Silence loop rejected" }
            output.addOnRoutingChangedListener(routeListener, Handler(Looper.getMainLooper()))
            track = output
            output.play()
            status = "active-route-pending"
            checkRoute(output)
            TrackLog.i("AUDIO_OUT", "USB background activity started: $status", about = null)
        } catch (error: Exception) {
            track = null
            created?.let(::dispose)
            closed = true // No repeated allocations/retries on audio callbacks.
            status = "unavailable"
            TrackLog.w("AUDIO_OUT", "USB background activity unavailable (${error.javaClass.simpleName})", about = null)
        }
    }

    @Synchronized
    private fun checkRoute(routing: AudioRouting) {
        val active = track ?: return
        if (routing !== active) return // Ignore late callbacks from a released track.
        try {
            val device = active.routedDevice ?: return
            if (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                status = "active-speaker"
            } else {
                // setPreferredDevice is a preference, not a routing guarantee.
                // Do not keep an Android track on the exclusive USB/Bluetooth route.
                close()
                status = "route-rejected"
                TrackLog.w("AUDIO_OUT", "USB background activity stopped: unexpected route type=${device.type}", about = null)
            }
        } catch (_: Exception) {
            close()
            status = "route-unavailable"
        }
    }

    @Synchronized
    fun stop() {
        val previous = track ?: return
        track = null
        dispose(previous)
        status = "idle"
        TrackLog.d("AUDIO_OUT", "USB background activity stopped", about = null)
    }

    @Synchronized
    override fun close() {
        closed = true
        stop()
        status = "closed"
    }

    private fun dispose(previous: AudioTrack) {
        runCatching { previous.removeOnRoutingChangedListener(routeListener) }
        runCatching { previous.pause() }
        runCatching { previous.release() }
    }

    companion object {
        private const val LOOP_FRAMES = 4_800 // 100 ms, looped by AudioFlinger rather than our process.

        fun forDevice(manager: AudioManager): UsbPlaybackActivity? {
            val affected = listOf(Build.MANUFACTURER, Build.BRAND).any { value ->
                value.equals("xiaomi", ignoreCase = true) || value.equals("redmi", ignoreCase = true) ||
                    value.equals("poco", ignoreCase = true)
            }
            return if (affected) UsbPlaybackActivity(manager) else null
        }
    }
}
