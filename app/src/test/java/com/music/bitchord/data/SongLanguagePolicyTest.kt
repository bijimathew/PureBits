package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SongLanguagePolicyTest {
    private fun song(title: String) = Song(videoId = "test", title = title, artist = "ASU", thumbnailUrl = null, fromAutoplay = false)

    @Test fun romanizedAndEnglishTitlesRemainUnknownWithoutLyrics() {
        listOf("Aval", "Podimeesha", "Slowly", "Under bouquet", "Bling-Bang-Bang-Born").forEach {
            assertEquals("und", SongLanguagePolicy.language(song(it), null))
        }
    }

    @Test fun nativeTitlesProvideHintsButChineseCharactersAloneDoNotImplyJapanese() {
        assertEquals("ja", SongLanguagePolicy.titleHint("スロウリー – Slowly"))
        assertEquals("ml", SongLanguagePolicy.titleHint("മലയാളം"))
        assertNull(SongLanguagePolicy.titleHint("明透"))
        assertNull(SongLanguagePolicy.titleHint("スロウリー മലയാളം"))
    }

    @Test fun manualCorrectionAndExplicitUnknownTakePriorityOverLyricsAndTitle() {
        val track = song("スロウリー")
        assertEquals("ml", SongLanguagePolicy.language(track, SongLanguageEntry(automatic = "ja", manual = "ml")))
        assertEquals("und", SongLanguagePolicy.language(track, SongLanguageEntry(automatic = "ja", manual = "und")))
        assertEquals("en", SongLanguagePolicy.language(track, SongLanguageEntry(automatic = "en")))
    }

    @Test fun automaticModeRestoresDetectionAfterCorrectionIsCleared() {
        val entry = SongLanguageEntry(automatic = "ja", manual = "ml")
        assertEquals("ja", SongLanguagePolicy.language(song("Slowly"), entry.copy(manual = null)))
    }

    @Test fun persistenceKeepsUnknownCorrectionDistinctFromAutomaticMode() {
        listOf(null, "und", "ml").forEach { correction ->
            val entry = SongLanguageEntry(automatic = "ja", manual = correction, checkedAt = 1234)
            assertEquals(entry, Json.decodeFromString<SongLanguageEntry>(Json.encodeToString(SongLanguageEntry.serializer(), entry)))
        }
    }

    @Test fun lrcMetadataAndTimestampsAreNotClassifiedAsLyrics() {
        val text = SongLanguagePolicy.lyricsText("[ar:English Artist]\n[ti:An English Title]\n[00:10.00]<00:10.10>മലയാളം\n[Chorus]\n[00:11.00]スロウリー")
        assertEquals("മലയാളം\nスロウリー", text)
        assertFalse(SongLanguagePolicy.enoughLyrics(text))
        assertTrue(SongLanguagePolicy.enoughLyrics("A full line of words in a song that gives enough text to identify a language confidently."))
    }

    @Test fun lyricsInputIsBounded() {
        assertEquals(10_000, SongLanguagePolicy.lyricsText("a".repeat(25_000)).length)
    }

    @Test fun filteredQueueLinksStillOpenTheOriginalLibraryPage() {
        assertEquals("local:downloads", SongLanguagePolicy.browseId("purebits:language:ml:local:downloads"))
        assertEquals("VLLM", SongLanguagePolicy.browseId("purebits:language:ja:VLLM"))
        assertEquals("spotify:playlist:liked", SongLanguagePolicy.browseId("purebits:language:en:spotify:playlist:liked"))
        assertEquals("VLLM", SongLanguagePolicy.browseId("VLLM"))
        assertNull(SongLanguagePolicy.browseId(null))
        assertNull(SongLanguagePolicy.browseId("purebits:language:ml"))
    }
}
