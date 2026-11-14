package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class SongLanguageEntry(
    val automatic: String? = null,
    /** Null follows detection; "und" is an explicit Unknown correction. */
    val manual: String? = null,
    val checkedAt: Long = 0L,
)

/** Text hints are deliberately conservative: a Latin title is not evidence of English vocals. */
object SongLanguagePolicy {
    const val UNKNOWN = "und"
    const val FILTER_SOURCE_PREFIX = "purebits:language:"

    /** Queue metadata keeps the filter; navigation still uses the real library page ID. */
    fun browseId(sourceId: String?): String? =
        if (sourceId?.startsWith(FILTER_SOURCE_PREFIX) == true) {
            sourceId.removePrefix(FILTER_SOURCE_PREFIX).substringAfter(':', "").takeIf { it.isNotBlank() }
        } else sourceId

    fun titleHint(title: String): String? {
        val malayalam = title.count { it in '\u0D00'..'\u0D7F' && it.isLetter() }
        val kana = title.count { (it in '\u3040'..'\u30FF' || it in '\uFF66'..'\uFF9D') && it.isLetter() }
        return when {
            malayalam >= 3 && kana == 0 -> "ml"
            kana >= 3 && malayalam == 0 -> "ja"
            else -> null
        }
    }

    fun language(song: Song, entry: SongLanguageEntry?): String =
        entry?.manual ?: entry?.automatic ?: titleHint(song.title) ?: UNKNOWN

    fun lyricsText(raw: String): String = raw.take(64_000).lineSequence()
        .filterNot { it.trim().matches(Regex("\\[(?:ar|al|ti|by|offset|length|re|ve):.*]", RegexOption.IGNORE_CASE)) }
        .map { it.replace(Regex("\\[[^]]*]|<[^>]*>"), " ").trim() }
        .filter { it.any(Char::isLetter) }
        .joinToString("\n")
        .take(10_000)

    fun enoughLyrics(text: String): Boolean = text.count(Char::isLetter) >= 60

    /** Filters describe this library, with no language-specific presets. */
    fun filterCodes(counts: Map<String, Int>): List<String> = listOf("") +
        counts.filter { (code, count) -> code.isNotBlank() && count > 0 }.keys
            .sortedWith(compareBy<String> { it == UNKNOWN }.thenBy(::label).thenBy { it })

    /** Reclassification or deletion can remove the last song in the selected language. */
    fun availableFilter(selected: String, counts: Map<String, Int>): String =
        selected.takeIf { (counts[it] ?: 0) > 0 }.orEmpty()

    fun label(code: String): String = when (code) {
        UNKNOWN -> "Unknown"
        "zxx" -> "Instrumental"
        else -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }.ifBlank { code }
    }

    val manualLanguages: List<String> =
        listOf("af", "ar", "az", "be", "bg", "bn", "ca", "cs", "cy", "da", "de", "el", "en", "es", "et",
            "eu", "fa", "fi", "fil", "fr", "ga", "gl", "gu", "he", "hi", "hr", "hu", "hy", "id", "is",
            "it", "ja", "ka", "kk", "km", "kn", "ko", "lo", "lt", "lv", "mk", "ml", "mn", "mr", "ms", "my", "ne",
            "nl", "no", "pa", "pl", "pt", "ro", "ru", "si", "sk", "sl", "sq", "sr", "sv", "sw", "ta",
            "te", "th", "tr", "uk", "ur", "uz", "vi", "zh").sortedBy(::label) + listOf("zxx", UNKNOWN)
}
