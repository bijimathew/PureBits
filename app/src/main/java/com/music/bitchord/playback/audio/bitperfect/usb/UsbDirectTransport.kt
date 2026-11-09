/*
 * Copyright (C) 2026 BitChord Project
 *
 * Adapted from Mornye (https://github.com/mornyeapp/Mornye),
 * android/app/src/main/kotlin/com/zarz/mornye/UsbDirectPlayback.kt,
 * Copyright 2026 zarzet.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package com.music.bitchord.playback.audio.bitperfect.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.music.bitchord.data.settings.UsbDirectVolumeMode
import com.music.bitchord.playback.audio.bitperfect.BitPerfectDriver
import com.music.bitchord.playback.audio.bitperfect.BitPerfectTransport
import com.music.bitchord.playback.audio.bitperfect.BitPerfectTransportException
import java.nio.ByteBuffer

/** JNI over `usb_transport.cpp`. Handles are opaque ids, never pointers. */
internal object UsbDirectNative {
    init {
        System.loadLibrary("bitchord_usb")
    }

    /** [candidates] holds [CANDIDATE_INTS] values per candidate, in preference order. */
    external fun open(fd: Int, candidates: IntArray): Long
    external fun chosen(handle: Long): Int
    external fun capacity(handle: Long): Int
    external fun write(handle: Long, data: ByteBuffer, offset: Int, count: Int): Int
    external fun setPlaying(handle: Long, playing: Boolean)
    external fun flush(handle: Long)
    external fun frames(handle: Long): Long
    external fun control(handle: Long, input: Boolean, request: Int, value: Int, index: Int, data: ByteArray): ByteArray
    external fun close(handle: Long)

    const val CANDIDATE_INTS = 17

    fun encode(plans: List<UsbDescriptors.Plan>): IntArray = plans.flatMap { plan ->
        val a = plan.alternate
        val f = plan.format
        listOf(
            a.control, a.interface_, a.alternate, if (a.uac2) 1 else 0, a.clock,
            plan.data.address, plan.data.attributes, plan.data.maxPacket, plan.data.interval,
            if (plan.data.rateControl) 1 else 0,
            plan.feedback?.address ?: -1, plan.feedback?.maxPacket ?: 0,
            f.sampleRate, f.channels, f.subslot, f.bits, f.encoding.code,
        )
    }.toIntArray()
}

/**
 * The direct USB driver: Android grants permission and opens the device, and
 * a native worker drives its isochronous endpoint with libusb, detaching
 * Android's own USB audio driver for as long as the stream is open.
 *
 * Auto and Software modes never issue hardware volume writes. Every PCM byte
 * passes through [UsbPcmWriteGate] before JNI. Hardware control is opt-in;
 * missing/invalid hardware controls fall back to software, never fixed output.
 * No mode restores a potentially loud DAC level on release.
 *
 * A failure never redirects to the phone speaker from here: the transport
 * stops, and the sink's owner decides what plays next.
 */
internal class UsbDirectTransport private constructor(
    private val connection: UsbDeviceConnection,
    @Volatile private var handle: Long,
    val usbDeviceId: Int,
    val volumeDeviceKey: String,
    override val deviceName: String,
    private val format: UsbDescriptors.OutputFormat,
    private val volumeControl: UsbVolume.Volume?,
    initialSoftwareDb: Double?,
) : BitPerfectTransport {

    override val driver = BitPerfectDriver.USB_DIRECT
    override val sampleRate = format.sampleRate
    override val channelCount = format.channels

    /** Samples are written left-aligned in the endpoint's container. */
    override val outputBits = format.subslot * 8
    override val validBits = format.bits
    override val bufferSizeFrames: Long = UsbDirectNative.capacity(handle).toLong()

    @Volatile private var failure: String? = null
    private var lastFrames = 0L
    @Volatile private var softwareDb = initialSoftwareDb
    @Volatile private var playerVolume = 1f
    override val softwareVolumeDb: Double? get() = softwareDb
    override val softwareAttenuationDb: Double?
        get() = gain.targetDb.takeIf { softwareDb != null || it < 0.0 }
    private val gain = UsbSoftwareGain(
        sampleRate, channelCount,
        when (outputBits) {
            16 -> UsbSoftwareGain.Format.PCM_16
            24 -> UsbSoftwareGain.Format.PCM_24
            32 -> UsbSoftwareGain.Format.PCM_32
            else -> error("Unsupported USB PCM slot")
        },
        initialDb = initialSoftwareDb ?: 0.0,
    )
    private val writer = UsbPcmWriteGate(channelCount, format.subslot, sampleRate, gain) { data, offset, count ->
        UsbDirectNative.write(handle, data, offset, count)
    }
    override val samplesUnchanged: Boolean get() = gain.atUnity

    private val control: UsbControl = UsbControl { input, request, value, index, data ->
        val reply = UsbDirectNative.control(handle, input, request, value, index, data)
        if (input) reply.copyInto(data)
    }

    val volume: UsbVolume.Snapshot? get() = volumeControl?.snapshot

    /** Called off the playback thread; the native worker serializes it with streaming. */
    fun setVolume(db: Double): UsbVolume.Snapshot {
        val volume = volumeControl ?: throw IllegalStateException("USB hardware volume unavailable")
        return try {
            synchronized(volume) { volume.set(control, db) }
        } catch (error: Exception) {
            // A partial per-channel write is not a usable volume state. Stop
            // before handing the interface to any other route or owner.
            fail("USB hardware volume failed")
            throw error
        }
    }

    @Synchronized
    fun setSoftwareVolume(db: Double): Double {
        check(failure == null && handle != 0L) { "USB output stopped" }
        check(softwareDb != null) { "USB software volume is not active" }
        val target = UsbSoftwareVolumePolicy.normalizeDb(db)
        softwareDb = target
        gain.setTargetDb(UsbSoftwareVolumePolicy.combinedDb(softwareDb, playerVolume))
        return target
    }

    @Synchronized
    override fun setPlayerVolume(volume: Float) {
        check(failure == null && handle != 0L) { "USB output stopped" }
        playerVolume = volume
        gain.setTargetDb(UsbSoftwareVolumePolicy.combinedDb(softwareDb, playerVolume))
    }

    fun markDetached() {
        fail("USB disconnected")
    }

    fun markFailedVolume() = fail("USB volume state could not be established")

    override fun write(buffer: ByteBuffer): Int {
        return call { writer.write(buffer) }
    }

    override fun play() = call { UsbDirectNative.setPlaying(handle, true) }

    override fun pause() = call { UsbDirectNative.setPlaying(handle, false) }

    override fun flush() {
        call { UsbDirectNative.flush(handle) }
        writer.flush()
        lastFrames = 0
    }

    /** The queue runs dry and the worker pads with silence that is not counted as played. */
    override fun stop() = Unit

    override fun playedFrames(): Long {
        failure?.let { throw BitPerfectTransportException(it) }
        if (handle == 0L) return lastFrames
        lastFrames = maxOf(lastFrames, call { UsbDirectNative.frames(handle) })
        return lastFrames
    }

    override fun release() {
        // Stop/cancel queued samples before releasing interfaces. In particular,
        // never restore an old (possibly 0 dB) hardware level while PCM is queued.
        gain.setTargetDb(UsbSoftwareVolumePolicy.MUTE_DB)
        if (handle != 0L) runCatching { UsbDirectNative.setPlaying(handle, false) }
        // The worker joins (cancelling and acknowledging every transfer, then
        // releasing the interfaces) before Android closes the descriptor.
        if (handle != 0L) runCatching { UsbDirectNative.close(handle) }
        handle = 0
        writer.flush()
        runCatching { connection.close() }
    }

    @Synchronized
    private fun fail(reason: String) {
        failure = reason
        gain.setTargetDb(UsbSoftwareVolumePolicy.MUTE_DB)
        if (handle != 0L) {
            runCatching { UsbDirectNative.setPlaying(handle, false) }
            runCatching { UsbDirectNative.flush(handle) }
        }
    }

    private inline fun <T> call(block: () -> T): T {
        failure?.let { throw BitPerfectTransportException(it) }
        return try {
            block()
        } catch (error: Exception) {
            fail(error.message ?: "USB output failed")
            throw BitPerfectTransportException(failure!!, error)
        }
    }

    companion object {
        fun open(
            manager: UsbManager,
            device: UsbDevice,
            rate: Int,
            channels: Int,
            sourceBits: Int,
            convert: Boolean,
            volumeMode: UsbDirectVolumeMode,
            softwareVolumeDb: Double,
            rememberedDb: Double?,
        ): UsbDirectTransport {
            val connection = manager.openDevice(device) ?: throw BitPerfectTransportException("cannot_open_usb")
            var handle = 0L
            try {
                val raw = connection.rawDescriptors ?: throw BitPerfectTransportException("no_descriptors")
                val plans = plans(raw, rate, channels, sourceBits, convert)
                if (plans.isEmpty()) throw BitPerfectTransportException("format")
                handle = try {
                    UsbDirectNative.open(connection.fileDescriptor, UsbDirectNative.encode(plans))
                } catch (error: IllegalStateException) {
                    throw BitPerfectTransportException("unsupported", error)
                }
                val plan = plans[UsbDirectNative.chosen(handle)]
                val opened = handle
                val control = UsbControl { input, request, value, index, data ->
                    val reply = UsbDirectNative.control(opened, input, request, value, index, data)
                    if (input) reply.copyInto(data)
                }
                // The native worker starts paused with an empty queue. In Auto
                // mode all DACs get software volume; descriptors/readback cannot
                // establish the real acoustic effect of a hardware control.
                val selection = UsbDirectVolumeSelection.select(volumeMode, softwareVolumeDb) {
                    UsbVolume.feature(raw, plan.alternate.control, plan.alternate.terminal, channels)
                        ?.let { feature ->
                            UsbVolume.Volume.open(control, feature).also { volume ->
                                if (rememberedDb != null) {
                                    // Explicit hardware mode preserves a level
                                    // chosen this attachment, respecting a knob
                                    // that was subsequently turned down.
                                    volume.set(control, minOf(rememberedDb, volume.snapshot.restoreLimitDb))
                                }
                            }
                        }
                }
                val volume = selection.hardware
                Log.i("UsbDirectTransport", "USB ${device.vendorId}:${device.productId}, " +
                    "${plan.format.sampleRate} Hz/${plan.format.bits} bits in ${plan.format.subslot * 8}, " +
                    "volume=${if (volume == null) "software" else "hardware"}")
                return UsbDirectTransport(
                    connection = connection,
                    handle = handle,
                    usbDeviceId = device.deviceId,
                    volumeDeviceKey = UsbDirectDevices.volumeKey(device),
                    deviceName = device.productName?.takeIf { it.isNotBlank() } ?: "USB audio",
                    format = plan.format,
                    volumeControl = volume,
                    initialSoftwareDb = selection.softwareDb,
                )
            } catch (error: Exception) {
                if (handle != 0L) UsbDirectNative.close(handle)
                runCatching { connection.close() }
                throw error as? BitPerfectTransportException ?: BitPerfectTransportException("unsupported", error)
            }
        }

        /** Every way the attached DAC could carry this stream, best first; only unchanged without [convert]. */
        fun plans(raw: ByteArray, rate: Int, channels: Int, sourceBits: Int, convert: Boolean): List<UsbDescriptors.Plan> {
            val device = try {
                UsbDescriptors.parse(raw)
            } catch (_: UsbDescriptors.MalformedDescriptorException) {
                return emptyList()
            }
            return UsbDescriptors.dacFormats(device, rate, channels, sourceBits, convert)
                // Software gain must never scale framed DoP or native DSD.
                .filter { (_, format) -> format.encoding == UsbDescriptors.Encoding.PCM }
                .mapNotNull { (alternate, format) -> UsbDescriptors.plan(alternate, format) }
                .take(32)
        }
    }
}
