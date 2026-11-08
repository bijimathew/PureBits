package com.music.bitchord.playback

import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AddonSwitchTest {
    private fun stream(sourceId: String) = SourceStream(
        url = "https://$sourceId.example/recording.flac",
        format = StreamFormat(codec = "flac"),
        sourceConfigId = sourceId,
    )

    @Before
    @After
    fun reset() {
        AddonSwitch.clear()
    }

    @Test
    fun `every manual selection gets a separate rendition even for the same stream`() {
        val chosen = stream("addon-one")
        val markers = List(20) { AddonSwitch.remember("song", chosen) }

        assertEquals(markers.size, markers.toSet().size)
        markers.forEach { marker ->
            assertTrue(marker.startsWith(AddonSwitch.PREFIX))
            assertSame(chosen, AddonSwitch.stream("song", marker))
        }
    }

    @Test
    fun `choosing another source for the same song preserves both recordings`() {
        val first = stream("addon-one")
        val second = stream("addon-two")
        val firstMarker = AddonSwitch.remember("song", first)
        val secondMarker = AddonSwitch.remember("song", second)

        assertNotEquals(firstMarker, secondMarker)
        assertSame(first, AddonSwitch.stream("song", firstMarker))
        assertSame(second, AddonSwitch.stream("song", secondMarker))
    }

    @Test
    fun `a marker cannot supply another song or an unknown selection`() {
        val chosen = stream("addon-one")
        val marker = AddonSwitch.remember("song", chosen)

        assertNull(AddonSwitch.stream("other-song", marker))
        assertNull(AddonSwitch.stream("song", "addon-missing"))
        assertSame(chosen, AddonSwitch.stream("song", marker))
    }

    @Test
    fun `recording a new serving source replaces the old one for that song`() {
        AddonSwitch.recordSource("song", "addon-one")
        AddonSwitch.recordSource("other-song", "addon-three")
        AddonSwitch.recordSource("song", "addon-two")

        assertEquals("addon-two", AddonSwitch.sourceFor("song"))
        assertEquals("addon-three", AddonSwitch.sourceFor("other-song"))
        assertNull(AddonSwitch.sourceFor("unknown-song"))
    }

    @Test
    fun `YouTube or an unknown source clears a previous addon attribution`() {
        AddonSwitch.recordSource("song", "addon-one")
        AddonSwitch.recordSource("other-song", "addon-two")

        // The resolver records null for YouTube and sources without an addon ID.
        AddonSwitch.recordSource("song", null)

        assertNull(AddonSwitch.sourceFor("song"))
        assertEquals("addon-two", AddonSwitch.sourceFor("other-song"))
    }

    @Test
    fun `clearing the session drops every stream attribution and active search`() {
        val firstMarker = AddonSwitch.remember("song", stream("addon-one"))
        val secondMarker = AddonSwitch.remember("other-song", stream("addon-two"))
        AddonSwitch.recordSource("song", "addon-one")
        AddonSwitch.recordSource("other-song", "addon-two")
        AddonSwitch.searchingMediaId.value = "song"

        AddonSwitch.clear()

        assertNull(AddonSwitch.stream("song", firstMarker))
        assertNull(AddonSwitch.stream("other-song", secondMarker))
        assertNull(AddonSwitch.sourceFor("song"))
        assertNull(AddonSwitch.sourceFor("other-song"))
        assertNull(AddonSwitch.searchingMediaId.value)
    }
}
