package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DownloadRecordingMatchTest {
    private fun song(
        title: String = "Shinunoga E-Wa",
        artist: String = "Fujii Kaze",
        album: String? = null,
    ) = Song("recording", title, artist, thumbnailUrl = null, durationText = "3:05", albumName = album)

    private class Catalogue(
        override val configId: String,
        private val row: Song,
        private val format: StreamFormat,
    ) : MusicSource {
        override val displayName = configId
        override val kind = SourceKind.ADDON
        val opened = mutableListOf<String>()
        override suspend fun health() = SourceHealth.Ok()
        override suspend fun search(query: String, limit: Int, waitForAll: Boolean, request: StreamRequest?) = listOf(row)
        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream {
            opened += trackId
            return SourceStream("https://$configId.example/$trackId", format)
        }
    }

    @Test fun sameDurationDoesNotMakeAnotherSingerTheDownloadedRecording() = runBlocking {
        val target = TrackMatcher.targetOf(song())
        val cover = song(artist = "English Cover Artist")
        // This is the permissive playback exception that downloads used to inherit.
        assertNotNull(TrackMatcher.score(cover, target))
        for (request in listOf(StreamRequest.Best, StreamRequest.Lossless)) {
            val source = Catalogue("cover", cover, StreamFormat("flac"))
            assertNull(SourceResolver.forDownload(target, request, sources = listOf(source)))
            assertTrue(source.opened.isEmpty())
        }
    }

    @Test fun wrongSingerLosslessCannotBeatTheCorrectRecording() = runBlocking {
        val cover = Catalogue("cover", song(artist = "English Cover Artist"), StreamFormat("flac"))
        val original = Catalogue("original", song(), StreamFormat("mp4", kbps = 320))
        val stream = SourceResolver.forDownload(
            TrackMatcher.targetOf(song()), StreamRequest.Lossless, sources = listOf(cover, original),
        )
        assertEquals("original", stream?.sourceConfigId)
        assertTrue(cover.opened.isEmpty())
        assertEquals(listOf("recording"), original.opened)
    }

    @Test fun missingArtistDoesNotAuthorizeAGuessForDownloads() = runBlocking {
        val source = Catalogue("unknown", song(artist = ""), StreamFormat("flac"))
        assertNull(SourceResolver.forDownload(TrackMatcher.targetOf(song()), StreamRequest.Lossless, sources = listOf(source)))
        assertTrue(source.opened.isEmpty())
    }

    @Test fun languageVersionsWithIdenticalCreditsAndDurationStayDistinct() {
        val japanese = TrackMatcher.targetOf(song("Shinunoga E-Wa (Japanese Version)"))
        assertFalse(TrackMatcher.matches(song("Shinunoga E-Wa (English Version)"), japanese.title, japanese.artist, 185))
        assertTrue(TrackMatcher.matches(song("Shinunoga E-Wa (Japanese ver.)"), japanese.title, japanese.artist, 185))
    }

    @Test fun plainLanguageLabelsAreNotDiscardedAsPackaging() {
        for (language in listOf("English", "Japanese", "Malayalam", "French", "Hindi", "Spanish")) {
            assertFalse(TrackMatcher.matches(song("Shinunoga E-Wa ($language)"), "Shinunoga E-Wa", "Fujii Kaze", 185))
            assertTrue(TrackMatcher.matches(song("Shinunoga E-Wa ($language Version)"), "Shinunoga E-Wa ($language)", "Fujii Kaze", 185))
        }
    }

    @Test fun languageEditionOnTheAlbumCannotMasqueradeAsTheOriginal() {
        val english = song(album = "Shinunoga E-Wa (English Version)")
        assertNull(TrackMatcher.score(english, TrackMatcher.targetOf(song())))
        assertNotNull(TrackMatcher.score(english, TrackMatcher.targetOf(song(album = "Shinunoga E-Wa (English Version)"))))
    }

    @Test fun lyricAndSubtitleTranslationsDoNotDeclareADifferentRecording() {
        for (label in listOf("English lyrics", "English subtitles", "English translation", "Official Audio")) {
            assertTrue(TrackMatcher.matches(song("Shinunoga E-Wa ($label)"), "Shinunoga E-Wa", "Fujii Kaze", 185))
        }
    }
}
