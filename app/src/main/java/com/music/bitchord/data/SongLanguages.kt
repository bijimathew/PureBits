package com.music.bitchord.data

import android.content.Context
import android.content.SharedPreferences
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.LyricsTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared by Downloads, online likes and offline likes; corrections survive metadata refreshes. */
object SongLanguages {
    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), SongLanguageEntry.serializer())
    private val lock = Any()
    private val inFlight = mutableSetOf<String>()
    private val _entries = MutableStateFlow<Map<String, SongLanguageEntry>>(emptyMap())
    val entries = _entries.asStateFlow()
    private val _refresh = MutableStateFlow(0)
    val refresh = _refresh.asStateFlow()
    private data class Aliases(val saved: Map<String, String>, val byUri: Map<String, Set<String>>)
    @Volatile private var aliases = Aliases(emptyMap(), emptyMap())
    private val aliasLock = Any()
    private val identifier by lazy {
        LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.85f).build())
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("purebits_song_languages", Context.MODE_PRIVATE)
        _entries.value = runCatching {
            json.decodeFromString(serializer, prefs.getString("entries", "{}") ?: "{}")
        }.getOrDefault(emptyMap())
    }

    private fun ids(song: Song): Set<String> {
        val saved = Downloads.saved.value
        val uri = song.localUri ?: saved[song.videoId] ?: return setOf(song.videoId)
        val index = synchronized(aliasLock) {
            if (aliases.saved !== saved) {
                aliases = Aliases(saved, saved.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.toSet() })
            }
            aliases
        }
        return setOf(song.videoId) + index.byUri[uri].orEmpty()
    }

    fun entry(song: Song, snapshot: Map<String, SongLanguageEntry> = _entries.value): SongLanguageEntry? {
        val matches = ids(song).mapNotNull(snapshot::get)
        return matches.firstOrNull { it.manual != null }
            ?: matches.firstOrNull { it.automatic != null } ?: matches.firstOrNull()
    }

    fun language(song: Song, snapshot: Map<String, SongLanguageEntry> = _entries.value): String =
        SongLanguagePolicy.language(song, entry(song, snapshot))

    private fun update(song: Song, transform: (SongLanguageEntry) -> SongLanguageEntry) = synchronized(lock) {
        val updated = transform(entry(song) ?: SongLanguageEntry())
        val next = _entries.value + ids(song).associateWith { updated }
        _entries.value = next
        if (::prefs.isInitialized) prefs.edit().putString("entries", json.encodeToString(serializer, next)).apply()
    }

    fun setManual(song: Song, language: String?) {
        update(song) { it.copy(manual = language, checkedAt = if (language == null) 0 else it.checkedAt) }
        if (language == null) _refresh.value += 1
    }

    /** A resolved download may acquire a second ID after its lyrics were classified. */
    fun onDownloadRecorded(song: Song) {
        if (entry(song) != null) update(song) { it }
    }

    /** Only text is classified, on device. No audio is uploaded or analysed. */
    suspend fun learnFromLyrics(song: Song, raw: String): Boolean {
        val existing = entry(song)
        if (existing?.manual != null || existing?.automatic != null && System.currentTimeMillis() - existing.checkedAt < 86_400_000L) return true
        val text = SongLanguagePolicy.lyricsText(raw)
        if (!SongLanguagePolicy.enoughLyrics(text)) return false
        val language = try {
            withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine<String> { continuation ->
                    identifier.identifyLanguage(text)
                        .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                        .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
                }
            }?.takeUnless { it == SongLanguagePolicy.UNKNOWN }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { null }
        if (language != null) update(song) { it.copy(automatic = language, checkedAt = System.currentTimeMillis()) }
        return language != null
    }

    /** Two bounded workers, cancelled on leaving the page. Misses are retried the next day. */
    suspend fun detectAll(context: Context, songs: List<Song>) = withContext(Dispatchers.IO) {
        val unique = songs.distinctBy { it.videoId }
        coroutineScope {
            repeat(2) { worker -> launch {
                unique.filterIndexed { index, _ -> index % 2 == worker }.forEach { song ->
                    coroutineContext.ensureActive()
                    val existing = entry(song)
                    if (existing?.manual != null || System.currentTimeMillis() - (existing?.checkedAt ?: 0) < 86_400_000L) return@forEach
                    if (!synchronized(lock) { inFlight.add(song.videoId) }) return@forEach
                    try {
                        val localUri = song.localUri ?: Downloads.saved.value[song.videoId]
                        val embedded = localUri?.let { EmbeddedLyrics.forUri(context, it) }
                        val localDetected = embedded?.let { learnFromLyrics(song, it.joinToString("\n") { line -> line.text }) } == true
                        val canFetch = AppSettings.meteredConnection.value != null && AppSettings.syncedLyrics.value && AppSettings.lyricsSources.value.isNotEmpty()
                        if (!localDetected && canFetch) {
                            // Obeys the existing lyrics enable switch, provider selection and timeouts.
                            LyricsTag.forTrack(song)
                            if (entry(song)?.automatic == null) update(song) { it.copy(checkedAt = System.currentTimeMillis()) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DebugLog.d("PureBits", "Language detection unavailable for ${song.videoId}: ${e.message}")
                    } finally {
                        synchronized(lock) { inFlight.remove(song.videoId) }
                    }
                }
            } }
        }
    }
}
