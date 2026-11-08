package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.DeviceCodecs
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class NextAddonResolverTest {

    private val target = TrackMatcher.Target("Bohemian Rhapsody", "Queen", durationSec = 360)
    private var previousForcedCodecs: Boolean? = null
    private var previousToleranceSeconds = AppSettings.DEFAULT_UPGRADE_LENGTH_SLACK_SECONDS

    @Before
    fun setUp() {
        previousForcedCodecs = DeviceCodecs.forced
        previousToleranceSeconds = AppSettings.upgradeLengthSlackSeconds.value
        DeviceCodecs.forced = false
        AppSettings.upgradeLengthSlackSeconds.value = AppSettings.DEFAULT_UPGRADE_LENGTH_SLACK_SECONDS
    }

    @After
    fun tearDown() {
        DeviceCodecs.forced = previousForcedCodecs
        AppSettings.upgradeLengthSlackSeconds.value = previousToleranceSeconds
    }

    @Test
    fun `starts after current source and wraps without retrying rejected sources`() = runTest {
        val first = FakeSource("first")
        val current = FakeSource("current")
        val rejected = FakeSource("rejected")
        val missing = FakeSource("missing", rows = emptyList())
        val attempts = mutableListOf<String>()

        val stream = SourceResolver.nextAddonFrom(
            sources = listOf(first, current, rejected, missing),
            target = target,
            request = StreamRequest.Best,
            currentSourceId = current.configId,
            excludedSourceIds = setOf(rejected.configId),
            onAttempt = { attempts += it },
        )

        assertEquals("first", stream?.sourceConfigId)
        assertEquals(listOf("missing", "first"), attempts)
        assertTrue(current.searches.isEmpty())
        assertTrue(rejected.searches.isEmpty())
    }

    @Test
    fun `disabled addons and builtin sources are never queried`() = runTest {
        val disabled = FakeSource("disabled")
        val youtube = FakeSource("youtube", kind = SourceKind.YOUTUBE)
        val saavn = FakeSource("saavn", kind = SourceKind.JIOSAAVN)
        val addon = FakeSource("addon")
        val instances = listOf(disabled, youtube, saavn, addon).associateBy { it.configId }
        val eligible = SourceRegistry.enabledConfigs(
            listOf(
                SourceConfig("disabled", SourceKind.ADDON, baseUrl = "https://disabled.example", enabled = false),
                SourceConfig("youtube", SourceKind.YOUTUBE),
                SourceConfig("saavn", SourceKind.JIOSAAVN, enabled = true),
                SourceConfig("addon", SourceKind.ADDON, baseUrl = "https://addon.example"),
            ),
        ).map { instances.getValue(it.id) }

        val stream = SourceResolver.nextAddonFrom(
            eligible, target, StreamRequest.Best, currentSourceId = null, excludedSourceIds = emptySet(),
        )

        assertEquals("addon", stream?.sourceConfigId)
        assertTrue(disabled.searches.isEmpty())
        assertTrue(youtube.searches.isEmpty())
        assertTrue(saavn.searches.isEmpty())
    }

    @Test
    fun `configured order wins over a faster later addon`() = runTest {
        val orderedFirst = FakeSource("ordered-first", searchDelayMs = 100)
        val faster = FakeSource("faster")

        val stream = SourceResolver.nextAddonFrom(
            listOf(orderedFirst, faster), target, StreamRequest.Lossless, null, emptySet(),
        )

        assertEquals("ordered-first", stream?.sourceConfigId)
        assertTrue(faster.searches.isEmpty())
        assertTrue(orderedFirst.searches.all { it.waitForAll && it.request == StreamRequest.Lossless })
        assertEquals(listOf(StreamRequest.Lossless), orderedFirst.streamRequests)
    }

    @Test
    fun `replacement must match title artist and recording length`() = runTest {
        val source = FakeSource(
            "addon",
            rows = listOf(
                recording("wrong-title", title = "Another Song"),
                recording("cover", artist = "Another Artist"),
                recording("unknown-artist", artist = ""),
                recording("wrong-cut", duration = "6:08"),
                recording("live", title = "Bohemian Rhapsody (Live)"),
                recording("same-recording", title = "Bohemian Rhapsody (Remastered 2011)", duration = "6:01"),
            ),
        )

        val stream = SourceResolver.nextAddonFrom(
            listOf(source), target, StreamRequest.Best, null, emptySet(),
        )

        assertEquals(listOf("same-recording"), source.streamedIds)
        assertEquals(361, stream?.durationSec)
    }

    @Test
    fun `a looser track length tolerance applies before the replacement is auditioned`() = runTest {
        AppSettings.upgradeLengthSlackSeconds.value = 5
        val source = FakeSource("addon", rows = listOf(recording("matching-cut", duration = "6:04")))

        val stream = SourceResolver.nextAddonFrom(
            listOf(source), target, StreamRequest.Best, null, emptySet(),
        )

        assertEquals("addon", stream?.sourceConfigId)
        assertEquals(listOf("matching-cut"), source.streamedIds)
        assertEquals(364, stream?.durationSec)
    }

    @Test
    fun `a tighter track length tolerance rejects the candidate before trying another addon`() = runTest {
        AppSettings.upgradeLengthSlackSeconds.value = 0
        val differentLength = FakeSource("different-length", rows = listOf(recording("wrong-cut", duration = "6:01")))
        val exactLength = FakeSource("exact-length", rows = listOf(recording("same-cut", duration = "6:00")))

        val stream = SourceResolver.nextAddonFrom(
            listOf(differentLength, exactLength), target, StreamRequest.Best, null, emptySet(),
        )

        assertEquals("exact-length", stream?.sourceConfigId)
        assertTrue(differentLength.searches.isNotEmpty())
        assertTrue(differentLength.streamedIds.isEmpty())
        assertEquals(listOf("same-cut"), exactLength.streamedIds)
    }

    @Test
    fun `unknown target duration still permits a title and artist match`() = runTest {
        val source = FakeSource("addon", rows = listOf(recording("matching", duration = null)))

        val stream = SourceResolver.nextAddonFrom(
            listOf(source), target.copy(durationSec = null), StreamRequest.Best, null, emptySet(),
        )

        assertEquals("addon", stream?.sourceConfigId)
    }

    @Test
    fun `failed and unplayable sources advance and stay excluded on later taps`() = runTest {
        val failed = FakeSource("failed", searchFailure = IllegalStateException("offline"))
        val unplayable = FakeSource("unplayable", playable = false)
        val playable = FakeSource("playable")
        val attempts = mutableSetOf<String>()
        val sources = listOf(failed, unplayable, playable)

        val stream = SourceResolver.nextAddonFrom(
            sources, target, StreamRequest.Best, null, emptySet(), onAttempt = { attempts += it },
        )
        val queriesAfterFirstTap = sources.map { it.searches.size }
        val exhausted = SourceResolver.nextAddonFrom(
            sources, target, StreamRequest.Best, stream?.sourceConfigId, attempts,
        )

        assertEquals("playable", stream?.sourceConfigId)
        assertEquals(setOf("failed", "unplayable", "playable"), attempts)
        assertNull(exhausted)
        assertEquals(queriesAfterFirstTap, sources.map { it.searches.size })
    }

    @Test
    fun `a stalled source times out and the next addon gets its turn`() = runTest {
        val stalled = FakeSource("stalled", searchDelayMs = 1_000)
        val next = FakeSource("next")
        val attempts = mutableListOf<String>()

        val stream = SourceResolver.nextAddonFrom(
            listOf(stalled, next), target, StreamRequest.Best, null, emptySet(),
            onAttempt = { attempts += it }, timeoutMs = 25,
        )

        assertEquals("next", stream?.sourceConfigId)
        assertEquals(listOf("stalled", "next"), attempts)
        assertTrue(stalled.streamedIds.isEmpty())
    }

    @Test
    fun `caller cancellation is propagated instead of trying another addon`() = runTest {
        val cancelled = FakeSource("cancelled", searchFailure = CancellationException("track changed"))
        val next = FakeSource("next")
        val attempts = mutableListOf<String>()

        try {
            SourceResolver.nextAddonFrom(
                listOf(cancelled, next), target, StreamRequest.Best, null, emptySet(),
                onAttempt = { attempts += it },
            )
            fail("Cancellation must reach the playback caller")
        } catch (expected: CancellationException) {
            assertEquals("track changed", expected.message)
        }

        assertEquals(listOf("cancelled"), attempts)
        assertTrue(next.searches.isEmpty())
    }

    @Test
    fun `with no alternative addon nothing is queried`() = runTest {
        val current = FakeSource("only-addon")

        val stream = SourceResolver.nextAddonFrom(
            listOf(current), target, StreamRequest.Best, current.configId, emptySet(),
        )

        assertNull(stream)
        assertTrue(current.searches.isEmpty())
        assertTrue(current.streamedIds.isEmpty())
    }

    private data class SearchCall(val waitForAll: Boolean, val request: StreamRequest?)

    private class FakeSource(
        override val configId: String,
        override val kind: SourceKind = SourceKind.ADDON,
        private val rows: List<Song> = listOf(recording("track")),
        private val playable: Boolean = true,
        private val searchDelayMs: Long = 0,
        private val searchFailure: Exception? = null,
    ) : MusicSource {
        override val displayName = configId
        val searches = mutableListOf<SearchCall>()
        val streamedIds = mutableListOf<String>()
        val streamRequests = mutableListOf<StreamRequest>()

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> {
            searches += SearchCall(waitForAll, request)
            searchFailure?.let { throw it }
            delay(searchDelayMs)
            return rows
        }

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
            streamedIds += trackId
            streamRequests += request
            return if (playable) {
                SourceStream("https://$configId.example/$trackId", StreamFormat(codec = "flac"))
            } else null
        }
    }

    private companion object {
        fun recording(
            id: String,
            title: String = "Bohemian Rhapsody",
            artist: String = "Queen",
            duration: String? = "6:00",
        ) = Song(id, title, artist, thumbnailUrl = null, durationText = duration)
    }
}
