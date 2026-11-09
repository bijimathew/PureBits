package com.music.bitchord.playback.audio.bitperfect.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Standard descriptors read from the user's connected Bunny DSP on Windows, 9 November 2026.
 * Fixture contains only the 18-byte device descriptor and 303-byte configuration. Manufacturer,
 * product and serial string indices were zeroed; no strings, serial values, or host IDs are saved.
 * These tests verify advertised capabilities and app planning, not Android playback or loudness.
 */
class BunnyUsbDescriptorsTest {
    private fun raw(): ByteArray = requireNotNull(javaClass.getResourceAsStream("/usb/bunny-dsp-31b2-1112-uac1.bin"))
        .use { it.readBytes() }

    @Test fun fixtureContainsOnlySanitizedStandardDescriptors() {
        val raw = raw()
        assertEquals(321, raw.size)
        assertEquals(18, raw[0].toInt())
        assertEquals(1, raw[1].toInt())
        for (i in 14..16) assertEquals(0, raw[i].toInt())
        assertEquals(1, raw[17].toInt())
        assertEquals(9, raw[18].toInt())
        assertEquals(2, raw[19].toInt())
        assertEquals(303, (raw[20].toInt() and 255) or ((raw[21].toInt() and 255) shl 8))
        descriptors(raw).forEach { assertFalse("String descriptor must not be included", it[1].toInt() == 3) }
    }

    @Test fun parsesActualIdentityAndStereoOutputAlternates() {
        val device = UsbDescriptors.parse(raw())
        assertEquals(0x31b2, device.vendor)
        assertEquals(0x1112, device.product)
        assertEquals(0x0101, device.revision)
        val playback = device.alternates.filter { it.interface_ == 2 }
        assertEquals(2, playback.size)
        assertEquals(listOf(16, 24), playback.map { it.bits })
        assertEquals(listOf(2, 3), playback.map { it.subslot })
        assertEquals(listOf(1, 2), playback.map { it.alternate })
        playback.forEach { alternate ->
            assertFalse(alternate.uac2)
            assertTrue(alternate.pcm)
            assertEquals(2, alternate.channels)
            assertEquals(0, alternate.control)
            assertEquals(5, alternate.terminal)
            assertEquals(listOf(44100 to 44100, 48000 to 48000, 96000 to 96000), alternate.rates)
        }
    }

    @Test fun directTransportPlansPreserveAllThreeAdvertisedRatesAt16And24Bits() {
        val raw = raw()
        for (rate in listOf(44100, 48000, 96000)) {
            val sixteen = UsbDirectTransport.plans(raw, rate, 2, 16, convert = false)
            assertEquals(listOf(16, 24), sixteen.map { it.format.bits })
            assertEquals(listOf(2, 3), sixteen.map { it.format.subslot })
            val twentyFour = UsbDirectTransport.plans(raw, rate, 2, 24, convert = false)
            assertEquals(1, twentyFour.size)
            assertEquals(24, twentyFour.single().format.bits)
            assertEquals(3, twentyFour.single().format.subslot)
            (sixteen + twentyFour).forEach { plan ->
                assertEquals(rate, plan.format.sampleRate)
                assertEquals(2, plan.format.channels)
                assertEquals(UsbDescriptors.Encoding.PCM, plan.format.encoding)
                assertEquals(2, plan.alternate.interface_)
                assertEquals(1, plan.data.address)
                assertEquals(0x09, plan.data.attributes) // Adaptive OUT; no implicit/explicit feedback needed.
                assertEquals(1, plan.data.interval)
                assertTrue(plan.data.rateControl)
                assertEquals(if (plan.format.subslot == 2) 384 else 576, plan.data.maxPacket)
                assertNull(plan.feedback)
            }
        }
    }

    @Test fun unsupportedRatesDepthsAndCaptureEndpointAreNotOfferedAsExactPlayback() {
        val raw = raw()
        for (rate in listOf(32000, 88200, 176400, 192000)) {
            assertTrue(UsbDirectTransport.plans(raw, rate, 2, 16, convert = false).isEmpty())
        }
        assertTrue(UsbDirectTransport.plans(raw, 44100, 2, 32, convert = false).isEmpty())
        // The configuration also has mono capture; it must never become a playback candidate.
        assertTrue(UsbDirectTransport.plans(raw, 44100, 1, 16, convert = false).isEmpty())
    }

    @Test fun optInConversionChoosesOnlyActualAdvertisedOutputFormats() {
        val raw = raw()
        val higherRate = UsbDirectTransport.plans(raw, 192000, 2, 24, convert = true)
        assertTrue(higherRate.isNotEmpty())
        assertEquals(96000, higherRate.first().format.sampleRate)
        assertEquals(24, higherRate.first().format.bits)
        val deeperSource = UsbDirectTransport.plans(raw, 44100, 2, 32, convert = true)
        assertTrue(deeperSource.isNotEmpty())
        assertEquals(44100, deeperSource.first().format.sampleRate)
        assertEquals(24, deeperSource.first().format.bits)
        (higherRate + deeperSource).forEach { plan ->
            assertTrue(plan.format.sampleRate in listOf(44100, 48000, 96000))
            assertTrue(plan.format.bits in listOf(16, 24))
            assertEquals(UsbDescriptors.Encoding.PCM, plan.format.encoding)
        }
    }

    @Test fun advertisedFeatureUnitVolumeIsNotTreatedAsProvenUsableHardwareGain() {
        val raw = raw()
        val feature = descriptors(raw).single {
            it[1].toInt() == 0x24 && it.size >= 4 && it[2].toInt() == 6 && it[3].toInt() == 7
        }
        assertEquals(6, feature[4].toInt()) // Source is mixer unit 6, not directly terminal 5.
        assertEquals(1, feature[5].toInt()) // One-byte UAC1 control bitmap per channel.
        assertEquals(1, feature[6].toInt()) // Master mute only.
        assertEquals(2, feature[7].toInt()) // Left channel advertises volume.
        assertEquals(2, feature[8].toInt()) // Right channel advertises volume.
        // The existing conservative topology parser refuses to guess a route through the mixer.
        assertNull(UsbVolume.feature(raw, interface_ = 0, terminal = 5, channels = 2))
    }

    private fun descriptors(raw: ByteArray): List<ByteArray> {
        val result = ArrayList<ByteArray>()
        var offset = 0
        while (offset < raw.size) {
            val size = raw[offset].toInt() and 255
            require(size >= 2 && offset + size <= raw.size)
            result += raw.copyOfRange(offset, offset + size)
            offset += size
        }
        return result
    }
}
