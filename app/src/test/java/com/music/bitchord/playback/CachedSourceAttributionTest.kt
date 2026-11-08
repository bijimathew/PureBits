package com.music.bitchord.playback

import com.music.bitchord.data.sources.SourceStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CachedSourceAttributionTest {
    private val playingUrl = "https://cdn.example/recording.flac"

    @Test
    fun `manual or upgraded choice owns the rendition instead of its previous source`() {
        assertEquals(
            "new-addon",
            sourceConfigIdForServedStream(
                servedUrl = playingUrl,
                explicitStream = SourceStream(playingUrl, sourceConfigId = "new-addon"),
                rememberedStream = SourceStream(playingUrl, sourceConfigId = "old-addon"),
                resolvedSourceRowId = "original-row-source",
            ),
        )
    }

    @Test
    fun `source row fallback is attributed to the source that actually answered`() {
        assertEquals(
            "fallback-addon",
            sourceConfigIdForServedStream(
                servedUrl = playingUrl,
                explicitStream = null,
                rememberedStream = SourceStream(playingUrl, sourceConfigId = "old-source"),
                resolvedSourceRowId = "fallback-addon",
            ),
        )
    }

    @Test
    fun `stale remembered URL cannot label newly served bytes as its addon`() {
        assertNull(
            sourceConfigIdForServedStream(
                servedUrl = playingUrl,
                explicitStream = null,
                rememberedStream = SourceStream("https://old.example/track.flac", sourceConfigId = "old-addon"),
                resolvedSourceRowId = null,
            ),
        )
    }

    @Test
    fun `unattributed explicit stream does not inherit an older addon ID`() {
        assertNull(
            sourceConfigIdForServedStream(
                servedUrl = playingUrl,
                explicitStream = SourceStream(playingUrl),
                rememberedStream = SourceStream(playingUrl, sourceConfigId = "old-addon"),
                resolvedSourceRowId = "old-row-source",
            ),
        )
    }

    @Test
    fun `only the pinned stream matching returned bytes supplies attribution`() {
        assertEquals(
            "playing-addon",
            sourceConfigIdForServedStream(
                servedUrl = playingUrl,
                explicitStream = SourceStream("https://stale.example/old.flac", sourceConfigId = "stale-addon"),
                rememberedStream = SourceStream(playingUrl, sourceConfigId = "playing-addon"),
                resolvedSourceRowId = null,
            ),
        )
    }
}
