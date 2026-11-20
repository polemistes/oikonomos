package app.oikonomos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.oikonomos.R
import app.oikonomos.ocr.LanguageModels
import app.oikonomos.ocr.ReceiptLanguage
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Velger språket kvitteringene leses som. Et språk som trenger en språkmodell, lastes ned idet det
 * velges, og blir først valgt når nedlastingen er ferdig og kontrollert.
 */
@Composable
fun LanguagePickerDialog(
    models: LanguageModels,
    selected: ReceiptLanguage,
    onSelect: (ReceiptLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf<ReceiptLanguage?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var failed by remember { mutableStateOf(false) }
    // Standardvalget står først, resten alfabetisk etter det norske navnet.
    val labels = ReceiptLanguage.entries.associateWith { stringResource(it.label) }
    val languages = listOf(ReceiptLanguage.DEFAULT) +
        (ReceiptLanguage.entries - ReceiptLanguage.DEFAULT).sortedBy { labels.getValue(it).lowercase(Locale.ROOT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language_title)) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.language_intro), style = MaterialTheme.typography.bodySmall)
                if (failed) {
                    Text(
                        stringResource(R.string.language_download_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                languages.forEach { language ->
                    val model = language.tesseract
                    val status = when {
                        language == ReceiptLanguage.DEFAULT -> null
                        downloading == language -> stringResource(R.string.language_downloading)
                        model == null -> stringResource(R.string.language_google)
                        models.isInstalled(model) -> stringResource(R.string.language_downloaded)
                        else -> stringResource(R.string.language_download, megabytes(model.bytes))
                    }
                    ListItem(
                        leadingContent = { RadioButton(selected = language == selected, onClick = null) },
                        headlineContent = { Text(labels.getValue(language)) },
                        supportingContent = status?.let { text ->
                            {
                                Column {
                                    Text(text)
                                    if (downloading == language) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable(enabled = downloading == null) {
                            if (model == null || models.isInstalled(model)) {
                                onSelect(language)
                            } else {
                                failed = false
                                progress = 0f
                                downloading = language
                                scope.launch {
                                    val done = models.download(model) { progress = it }
                                    downloading = null
                                    if (done) onSelect(language) else failed = true
                                }
                            }
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** «1,4» for en fil på 1,4 megabyte. */
private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / 1_000_000.0).replace('.', ',')
