package com.music.bitchord.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.SongLanguagePolicy
import com.music.bitchord.data.SongLanguages
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.theme.ArtworkPalette

@Composable
fun SongLanguageFilter(
    counts: Map<String, Int>,
    selected: String,
    onSelect: (String) -> Unit,
    detecting: Boolean,
    palette: ArtworkPalette,
) {
    val codes = remember(counts) { SongLanguagePolicy.filterCodes(counts) }
    Column(Modifier.padding(bottom = 8.dp)) {
        if (detecting) Row(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.language_detecting),
                style = MaterialTheme.typography.titleSmall,
                color = palette.onBackground,
            )
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = palette.onBackground,
                strokeWidth = 2.dp,
            )
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(codes, key = { it }) { code ->
                val label = if (code.isEmpty()) stringResource(R.string.language_all) else SongLanguagePolicy.label(code)
                val count = if (code.isEmpty()) counts.values.sum() else counts[code] ?: 0
                FilterChip(
                    selected = selected == code,
                    onClick = { onSelect(code) },
                    label = { Text("$label ($count)") },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Transparent,
                        labelColor = palette.onBackground,
                        selectedContainerColor = palette.onBackground,
                        selectedLabelColor = palette.background,
                    ),
                    border = BorderStroke(1.dp, palette.onBackground.copy(alpha = 0.35f)),
                )
            }
        }
    }
}

@Composable
fun SongLanguageDialog(song: Song, onDismiss: () -> Unit) {
    val entries by SongLanguages.entries.collectAsStateWithLifecycle()
    val entry = SongLanguages.entry(song, entries)
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    LaunchedEffect(song.videoId, entry?.manual) { SongLanguages.detectAll(context, listOf(song)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.song_language)) },
        text = {
            Column {
                Text(stringResource(R.string.language_picker_help), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.language_search)) }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (query.isBlank()) item {
                        LanguageChoice(
                            stringResource(R.string.language_automatic, SongLanguagePolicy.label(entry?.automatic ?: SongLanguagePolicy.titleHint(song.title) ?: "und")),
                            entry?.manual == null,
                        ) { SongLanguages.setManual(song, null); onDismiss() }
                    }
                    items(SongLanguagePolicy.manualLanguages.filter { SongLanguagePolicy.label(it).contains(query, true) || it.equals(query, true) }) { code ->
                        LanguageChoice(SongLanguagePolicy.label(code), entry?.manual == code) {
                            SongLanguages.setManual(song, code)
                            onDismiss()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
    )
}

@Composable
private fun LanguageChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
