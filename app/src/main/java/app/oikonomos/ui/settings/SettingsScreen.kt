package app.oikonomos.ui.settings

import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.oikonomos.R
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date

private const val ZIP = "application/zip"
private val snapshotFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy 'kl.' HH.mm")

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onCategories: () -> Unit, onProjects: () -> Unit) {
    val state = viewModel.state
    val years by viewModel.years.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pickExportYear by rememberSaveable { mutableStateOf(false) }
    var exportYear by rememberSaveable { mutableStateOf(0) }
    var restoreFrom by rememberSaveable { mutableStateOf<Uri?>(null) }
    var restoreSnapshot by remember { mutableStateOf<Pair<Uri, LocalDateTime>?>(null) }
    var autoBackupMenu by rememberSaveable { mutableStateOf(false) }
    val backup by viewModel.backupState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            launch { snackbar.showSnackbar(context.getString(message.id, *message.args.toTypedArray())) }
        }
    }

    val exportTarget = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZIP)) { uri ->
        if (uri != null) viewModel.export(exportYear, uri)
    }
    val backupTarget = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZIP)) { uri ->
        if (uri != null) viewModel.backup(uri)
    }
    val restoreSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        restoreFrom = uri
    }
    val backupFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.chooseBackupFolder(uri)
    }
    val restoreFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.loadSnapshots(uri)
    }
    val startExport = { year: Int ->
        exportYear = year
        exportTarget.launch("oikonomos-$year.zip")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!state.loaded) return@Scaffold
        val settings = state.settings
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            SectionTitle(R.string.settings_company)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = settings.companyName,
                    onValueChange = { text -> viewModel.change { it.copy(companyName = text) } },
                    label = { Text(stringResource(R.string.company_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = settings.orgNumber,
                    onValueChange = { text -> viewModel.change { it.copy(orgNumber = text) } },
                    label = { Text(stringResource(R.string.org_number)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ListItem(
                modifier = Modifier.clickable { viewModel.change { it.copy(vatRegistered = !it.vatRegistered) } },
                headlineContent = { Text(stringResource(R.string.vat_registered)) },
                supportingContent = { Text(stringResource(R.string.vat_registered_hint)) },
                // Hele raden er trykkbar, så bryteren viser bare tilstanden.
                trailingContent = { Switch(checked = settings.vatRegistered, onCheckedChange = null) },
            )

            SectionTitle(R.string.settings_invoice)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                val digits = KeyboardOptions(keyboardType = KeyboardType.Number)
                OutlinedTextField(
                    value = settings.address,
                    onValueChange = { text -> viewModel.change { it.copy(address = text) } },
                    label = { Text(stringResource(R.string.company_address)) },
                    keyboardOptions = words,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = settings.postalCode,
                        onValueChange = { text -> viewModel.change { it.copy(postalCode = text.filter(Char::isDigit).take(4)) } },
                        label = { Text(stringResource(R.string.customer_postal_code)) },
                        singleLine = true,
                        keyboardOptions = digits,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = settings.city,
                        onValueChange = { text -> viewModel.change { it.copy(city = text) } },
                        label = { Text(stringResource(R.string.customer_city)) },
                        singleLine = true,
                        keyboardOptions = words,
                        modifier = Modifier.weight(3f),
                    )
                }
                OutlinedTextField(
                    value = settings.email,
                    onValueChange = { text -> viewModel.change { it.copy(email = text.trim()) } },
                    label = { Text(stringResource(R.string.company_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = settings.phone,
                    onValueChange = { text -> viewModel.change { it.copy(phone = text) } },
                    label = { Text(stringResource(R.string.company_phone)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = settings.bankAccount,
                    onValueChange = { text -> viewModel.change { it.copy(bankAccount = text.filter(Char::isDigit).take(11)) } },
                    label = { Text(stringResource(R.string.bank_account)) },
                    singleLine = true,
                    keyboardOptions = digits,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(R.string.payment_days, settings.paymentDays, Modifier.weight(1f)) { value ->
                        viewModel.change { it.copy(paymentDays = value.coerceIn(0, 365)) }
                    }
                    NumberField(R.string.invoice_number_start, settings.invoiceNumberStart, Modifier.weight(1f)) { value ->
                        viewModel.change { it.copy(invoiceNumberStart = value.coerceAtLeast(1)) }
                    }
                }
                Text(
                    stringResource(R.string.invoice_number_start_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = settings.invoiceNote,
                    onValueChange = { text -> viewModel.change { it.copy(invoiceNote = text) } },
                    label = { Text(stringResource(R.string.invoice_fixed_note)) },
                    supportingText = { Text(stringResource(R.string.invoice_fixed_note_hint)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ListItem(
                modifier = Modifier.clickable { viewModel.change { it.copy(limitedCompany = !it.limitedCompany) } },
                headlineContent = { Text(stringResource(R.string.limited_company)) },
                supportingContent = { Text(stringResource(R.string.limited_company_hint)) },
                trailingContent = { Switch(checked = settings.limitedCompany, onCheckedChange = null) },
            )

            SectionTitle(R.string.settings_bookkeeping)
            SettingsRow(R.string.categories, stringResource(R.string.categories_hint), onClick = onCategories)
            SettingsRow(R.string.projects, stringResource(R.string.projects_hint), onClick = onProjects)
            SettingsRow(R.string.export_year, stringResource(R.string.export_year_hint), enabled = !state.busy) {
                when (years.size) {
                    0 -> scope.launch { snackbar.showSnackbar(context.getString(R.string.export_empty)) }
                    1 -> startExport(years.first())
                    else -> pickExportYear = true
                }
            }

            SectionTitle(R.string.settings_backup)
            Text(
                stringResource(R.string.backup_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            val timeText = { millis: Long ->
                val time = Date(millis)
                DateFormat.getDateFormat(context).format(time) + " " + DateFormat.getTimeFormat(context).format(time)
            }
            val autoHint = if (!backup.automatic) {
                stringResource(R.string.auto_backup_off_hint)
            } else {
                stringResource(R.string.auto_backup_folder, backup.folderName) + "\n" +
                    if (backup.lastSyncAt == 0L) stringResource(R.string.auto_backup_waiting)
                    else stringResource(R.string.auto_backup_last, timeText(backup.lastSyncAt))
            }
            SettingsRow(R.string.auto_backup, autoHint, enabled = !state.busy) {
                if (backup.automatic) autoBackupMenu = true else backupFolder.launch(null)
            }
            if (backup.automatic && backup.failed) {
                Text(
                    stringResource(R.string.auto_backup_failed_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            val lastZip = if (backup.lastZipAt == 0L) {
                stringResource(R.string.backup_never)
            } else {
                stringResource(R.string.backup_last, timeText(backup.lastZipAt))
            }
            SettingsRow(R.string.backup_save, lastZip, enabled = !state.busy) {
                backupTarget.launch("oikonomos-sikkerhetskopi-${LocalDate.now()}.zip")
            }
            SettingsRow(R.string.backup_restore, stringResource(R.string.backup_restore_hint), enabled = !state.busy) {
                restoreSource.launch(arrayOf(ZIP, "application/x-zip-compressed", "application/octet-stream"))
            }
            SettingsRow(R.string.backup_restore_folder, stringResource(R.string.backup_restore_folder_hint), enabled = !state.busy) {
                restoreFolder.launch(null)
            }
        }
    }

    if (autoBackupMenu) {
        val choose = { action: () -> Unit ->
            autoBackupMenu = false
            action()
        }
        AlertDialog(
            onDismissRequest = { autoBackupMenu = false },
            title = { Text(stringResource(R.string.auto_backup)) },
            text = {
                Column {
                    Text(stringResource(R.string.auto_backup_folder, backup.folderName))
                    TextButton(onClick = { choose(viewModel::syncNow) }) { Text(stringResource(R.string.auto_backup_now)) }
                    TextButton(onClick = { choose { backupFolder.launch(null) } }) { Text(stringResource(R.string.auto_backup_change)) }
                    TextButton(onClick = { choose(viewModel::disableAutoBackup) }) { Text(stringResource(R.string.auto_backup_off)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { autoBackupMenu = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }

    viewModel.restoreChoices?.let { choices ->
        AlertDialog(
            onDismissRequest = viewModel::dismissSnapshots,
            title = { Text(stringResource(R.string.restore_pick_time)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.restore_pick_time_hint))
                    choices.times.forEach { time ->
                        ListItem(
                            headlineContent = { Text(time.format(snapshotFormat)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                viewModel.dismissSnapshots()
                                restoreSnapshot = choices.folder to time
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissSnapshots) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    restoreSnapshot?.let { (folder, time) ->
        AlertDialog(
            onDismissRequest = { restoreSnapshot = null },
            title = { Text(stringResource(R.string.restore_title)) },
            text = { Text(stringResource(R.string.restore_snapshot_body, time.format(snapshotFormat))) },
            confirmButton = {
                TextButton(onClick = {
                    restoreSnapshot = null
                    viewModel.restoreFromFolder(folder, time)
                }) { Text(stringResource(R.string.restore_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { restoreSnapshot = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (pickExportYear) {
        AlertDialog(
            onDismissRequest = { pickExportYear = false },
            title = { Text(stringResource(R.string.choose_year)) },
            text = {
                Column {
                    years.forEach { year ->
                        ListItem(
                            headlineContent = { Text(year.toString()) },
                            modifier = Modifier.clickable {
                                pickExportYear = false
                                startExport(year)
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickExportYear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    restoreFrom?.let { source ->
        AlertDialog(
            onDismissRequest = { restoreFrom = null },
            title = { Text(stringResource(R.string.restore_title)) },
            text = { Text(stringResource(R.string.restore_body)) },
            confirmButton = {
                TextButton(onClick = {
                    restoreFrom = null
                    viewModel.restore(source)
                }) { Text(stringResource(R.string.restore_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { restoreFrom = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Tallfelt som kan stå tomt mens brukeren skriver. Verdien meldes videre når feltet inneholder et tall. */
@Composable
private fun NumberField(label: Int, value: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter(Char::isDigit).take(6)
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun SectionTitle(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsRow(title: Int, hint: String, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(hint) },
    )
}
