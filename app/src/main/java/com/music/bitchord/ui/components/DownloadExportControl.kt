package com.music.bitchord.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.download.DownloadExports
import com.music.bitchord.download.DownloadStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DownloadExportControl() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress by DownloadExports.progress.collectAsStateWithLifecycle()
    val start = {
        scope.launch {
            try { DownloadExports.export(context.applicationContext) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(context, e.message ?: "Export failed", Toast.LENGTH_LONG).show() }
        }
        Unit
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start()
        else Toast.makeText(context, R.string.export_downloads_permission, Toast.LENGTH_LONG).show()
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        TextButton(
            enabled = progress?.running != true,
            onClick = {
                if (DownloadStore.needsLegacyPermission() && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else start()
            },
        ) { Text(stringResource(R.string.export_saved_downloads)) }
        Text(stringResource(R.string.export_saved_downloads_help), style = MaterialTheme.typography.bodySmall)
        progress?.let { value ->
            Text(
                if (value.running) stringResource(R.string.export_downloads_progress, value.processed, value.total)
                else stringResource(R.string.export_downloads_result, value.copied, value.existing, value.unsupported, value.failed),
                Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
