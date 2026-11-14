package com.music.bitchord.download

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

class DownloadExportsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun copyingPreservesEveryByteAcrossBufferBoundaries() {
        val bytes = ByteArray(200_007) { (it * 31).toByte() }
        val output = ByteArrayOutputStream()
        val hash = DownloadExports.copyVerifiedInput(bytes.inputStream(), output, bytes.size.toLong()) {}
        assertArrayEquals(bytes, output.toByteArray())
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), hash)
    }

    @Test(expected = IllegalStateException::class) fun truncatedSourceCannotBePublished() {
        DownloadExports.copyVerifiedInput(byteArrayOf(1, 2).inputStream(), ByteArrayOutputStream(), 3) {}
    }

    @Test(expected = IllegalStateException::class) fun emptySourceCannotBePublished() {
        DownloadExports.copyVerifiedInput(byteArrayOf().inputStream(), ByteArrayOutputStream(), 0) {}
    }

    @Test fun cancellationStopsCopyingBeforeTheNextChunk() {
        val output = ByteArrayOutputStream()
        var checks = 0
        try {
            DownloadExports.copyVerifiedInput(ByteArray(200_000).inputStream(), output, 200_000) {
                if (++checks == 2) throw CancellationException("leave screen")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(65_536, output.size())
        }
    }

    @Test fun onlyFilesUnderThePrivateDirectoryAreExportCandidates() {
        val root = temp.newFolder("files")
        assertTrue(DownloadExports.isPrivateFile(File(root, "downloads/song.flac"), root))
        assertFalse(DownloadExports.isPrivateFile(File(temp.root, "files-other/song.flac"), root))
        assertFalse(DownloadExports.isPrivateFile(File(root, "../shared/song.flac"), root))
    }

    @Test fun legacyExportNeverReplacesAnExistingFile() {
        val first = temp.newFile("song.flac")
        first.writeText("original")
        temp.newFile("song (1).flac")
        assertEquals("song (2).flac", DownloadStore.availableFile(first).name)
        assertEquals("original", first.readText())
    }
}
