package com.music.bitchord.playback

import com.music.bitchord.data.sources.SourceStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow

/** Explicit addon choices have their own rendition, so two recordings never share cached bytes. */
object AddonSwitch {
    const val PREFIX = "addon-"
    val searchingMediaId = MutableStateFlow<String?>(null)

    private data class Choice(val mediaId: String, val stream: SourceStream)
    private val choices = ConcurrentHashMap<String, Choice>()
    private val servingSources = ConcurrentHashMap<String, String>()

    fun remember(mediaId: String, stream: SourceStream): String {
        val marker = PREFIX + UUID.randomUUID()
        choices[marker] = Choice(mediaId, stream)
        return marker
    }

    fun stream(mediaId: String, marker: String): SourceStream? =
        choices[marker]?.takeIf { it.mediaId == mediaId }?.stream

    fun recordSource(mediaId: String, configId: String?) {
        if (configId == null) servingSources.remove(mediaId) else servingSources[mediaId] = configId
    }

    fun sourceFor(mediaId: String): String? = servingSources[mediaId]

    fun clear() {
        choices.clear()
        servingSources.clear()
        searchingMediaId.value = null
    }
}
