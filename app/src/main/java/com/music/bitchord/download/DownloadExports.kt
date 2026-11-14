package com.music.bitchord.download

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class DownloadExportProgress(
    val running: Boolean = false,
    val processed: Int = 0,
    val total: Int = 0,
    val copied: Int = 0,
    val existing: Int = 0,
    val unsupported: Int = 0,
    val failed: Int = 0,
)

/** Copies are intentional: offline likes and currently playing items can still hold the old URI. */
object DownloadExports {
    private val mutex = Mutex()
    private val _progress = MutableStateFlow<DownloadExportProgress?>(null)
    val progress = _progress.asStateFlow()
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    suspend fun export(context: Context) = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext
        try {
            AppSettings.setExportDownloads(true)
            val prefs = context.getSharedPreferences("purebits_download_exports", Context.MODE_PRIVATE)
            val exported = runCatching { Json.decodeFromString(serializer, prefs.getString("copies", "{}") ?: "{}") }
                .getOrDefault(emptyMap()).toMutableMap()
            val songs = Downloads.getDownloadedSongs(context).distinctBy { it.localUri }
            var state = DownloadExportProgress(running = true, total = songs.size)
            _progress.value = state
            for (song in songs) {
                coroutineContext.ensureActive()
                val sourceUri = song.localUri ?: continue
                var pending: DownloadStore.Pending? = null
                var published: Uri? = null
                try {
                    val uri = Uri.parse(sourceUri)
                    val source = uri.path?.takeIf { uri.scheme == "file" }?.let(::File)
                    if (source == null || !isPrivateFile(source, context.filesDir)) {
                        state = state.copy(existing = state.existing + 1)
                        continue
                    }
                    val previous = exported[sourceUri]?.let(Uri::parse)
                    if (previous != null && DownloadStore.exists(context, previous)) {
                        state = state.copy(existing = state.existing + 1)
                        continue
                    }
                    val container = source.inputStream().use {
                        (Downloader.sniff(it.readBytesAtMost(512), "", null, source.length()) as? Downloader.Probe.File)?.container
                    }
                    if (container == null) {
                        // Offline HLS/DASH directories are not standalone songs. Keep them intact.
                        state = state.copy(unsupported = state.unsupported + 1)
                        continue
                    }
                    val baseName = DownloadStore.fileNameFor(song, container.extension)
                    val suffix = MessageDigest.getInstance("SHA-256").digest(sourceUri.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
                    val name = "${baseName.substringBeforeLast('.')}-$suffix.${container.extension}"
                    val destination = DownloadStore.begin(context, name, container.mimeType, public = true, downloadsCollection = container == Downloader.Container.WEBM)
                    pending = destination
                    val originalSize = source.length()
                    val taskContext = coroutineContext
                    val digest = source.inputStream().use { input -> destination.openStream().use { output ->
                        copyVerifiedInput(input, output, originalSize) { taskContext.ensureActive() }
                    } }
                    // Verify the stored bytes before exposing a copy to other apps.
                    val storedDigest = context.contentResolver.openInputStream(destination.tagUri)?.use { input ->
                        digestInput(input) { taskContext.ensureActive() }
                    } ?: error("Could not verify exported audio")
                    check(digest.contentEquals(storedDigest)) { "Export verification failed" }
                    val finished = destination.commit()
                    published = finished
                    exported[sourceUri] = finished.toString()
                    check(prefs.edit().putString("copies", Json.encodeToString(serializer, exported)).commit()) { "Could not save export record" }
                    pending = null
                    published = null
                    state = state.copy(copied = state.copied + 1)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.w("PureBits", "Download export failed for ${song.videoId}: ${e.message}")
                    state = state.copy(failed = state.failed + 1)
                } finally {
                    published?.let { DownloadStore.delete(context, it) }
                    pending?.abort()
                    state = state.copy(processed = state.processed + 1)
                    _progress.value = state
                }
            }
        } finally {
            _progress.value = _progress.value?.copy(running = false)
            mutex.unlock()
        }
    }

    internal fun isPrivateFile(file: File, filesDir: File): Boolean = file.canonicalFile.toPath().startsWith(filesDir.canonicalFile.toPath())

    internal fun copyVerifiedInput(input: InputStream, output: OutputStream, expected: Long, checkActive: () -> Unit): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            size += count
        }
        check(expected > 0 && size == expected) { "The saved file changed or is incomplete" }
        output.flush()
        return digest.digest()
    }

    private fun digestInput(input: InputStream, checkActive: () -> Unit): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest()
    }

    private fun InputStream.readBytesAtMost(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var size = 0
        while (size < limit) {
            val count = read(buffer, size, limit - size)
            if (count < 0) break
            size += count
        }
        return buffer.copyOf(size)
    }
}
