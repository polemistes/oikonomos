package app.oikonomos.ui.voucher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.oikonomos.R
import app.oikonomos.data.AttachmentStore
import app.oikonomos.data.Category
import app.oikonomos.data.StoredFile
import app.oikonomos.data.VoucherType
import app.oikonomos.domain.ExchangeRates
import app.oikonomos.domain.Money
import app.oikonomos.domain.OrgNumber
import app.oikonomos.ocr.DocumentScanner
import app.oikonomos.ui.AttachmentThumbnail
import app.oikonomos.ui.CurrencyPickerDialog
import app.oikonomos.ui.DateField
import app.oikonomos.ui.DropdownField
import app.oikonomos.ui.LanguagePickerDialog
import app.oikonomos.ui.formatted
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private val attachmentTypes = arrayOf("image/*", "application/pdf")
private val vatRates = listOf(0, 12, 15, 25)

@Composable
fun VoucherEditScreen(
    viewModel: VoucherEditViewModel,
    start: String,
    store: AttachmentStore,
    onClose: () -> Unit,
) {
    val state = viewModel.state
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val vatRegistered by viewModel.vatRegistered.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val currentOnClose by rememberUpdatedState(onClose)
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    var confirmUnbook by rememberSaveable { mutableStateOf(false) }
    var pickCurrency by rememberSaveable { mutableStateOf(false) }
    var pickLanguage by rememberSaveable { mutableStateOf(false) }
    val permissions = state.permissions
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                EditEvent.Closed -> currentOnClose()
                // Egen jobb, så en melding som står på skjermen ikke holder igjen neste hendelse.
                is EditEvent.Message -> launch {
                    snackbar.showSnackbar(context.getString(event.message.id, *event.message.args.toTypedArray()))
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.addFiles(uris)
    }
    // Når dokumentskanneren ikke er tilgjengelig, brukes telefonens vanlige kameraapp.
    var cameraTarget by rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val target = cameraTarget
        if (taken && target != null) viewModel.addFiles(listOf(target))
    }
    val scan = rememberDocumentScanner(
        onPages = viewModel::addFiles,
        onUnavailable = {
            val target = store.newCameraTarget()
            cameraTarget = target
            try {
                camera.launch(target)
            } catch (_: ActivityNotFoundException) {
                showMessage(context.getString(R.string.camera_unavailable))
            }
        },
    )

    // Skann eller filvelger åpnes én gang når skjemaet er startet fra «Nytt bilag».
    var startHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!startHandled) {
            startHandled = true
            when (start) {
                START_SCAN -> scan()
                START_PICK -> picker.launch(attachmentTypes)
            }
        }
    }

    val leave = { if (state.dirty) confirmDiscard = true else onClose() }
    BackHandler(enabled = state.dirty) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            viewModel.isNew -> stringResource(R.string.new_voucher)
                            state.label.isEmpty() -> stringResource(R.string.pending_title)
                            else -> stringResource(R.string.voucher_title, state.label)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (permissions.delete) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                    if (permissions.cancel || permissions.unbook) {
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_actions))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (permissions.unbook) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.unbook)) },
                                        onClick = {
                                            menuOpen = false
                                            confirmUnbook = true
                                        },
                                    )
                                }
                                if (permissions.cancel) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.cancel_voucher)) },
                                        onClick = {
                                            menuOpen = false
                                            confirmCancel = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        // Knappene ligger fast nederst, også over tastaturet, så begge valgene alltid er synlige.
        bottomBar = {
            if (!state.loading && permissions.anyEdit) {
                val idle = !state.saving && !state.reading
                Surface {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.pending && permissions.book) {
                            // Å legge kvitteringen til side er det trygge valget og står derfor framhevet.
                            // Føring gir bilagsnummer og skal være et bevisst valg.
                            Button(onClick = { viewModel.save(book = false) }, enabled = idle, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.action_save_pending), textAlign = TextAlign.Center)
                            }
                            OutlinedButton(onClick = { viewModel.save(book = true) }, enabled = idle, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.action_book), textAlign = TextAlign.Center)
                            }
                        } else {
                            Button(onClick = { viewModel.save(book = true) }, enabled = idle, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.save))
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (state.loading) return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val cancelledAt = state.cancelledAt
            val info = when {
                cancelledAt != null -> {
                    val day = Instant.ofEpochMilli(cancelledAt).atZone(ZoneId.systemDefault()).toLocalDate().formatted()
                    listOfNotNull(
                        stringResource(R.string.cancelled_info, day),
                        state.cancelReason.takeIf { it.isNotBlank() }?.let { stringResource(R.string.cancelled_reason, it) },
                    ).joinToString("\n")
                }
                state.fromInvoice -> stringResource(R.string.voucher_locked)
                state.pending && !viewModel.isNew -> stringResource(R.string.pending_info)
                else -> null
            }
            if (info != null) {
                Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AttachmentRow(
                attachments = state.attachments,
                editable = permissions.editAll,
                store = store,
                onOpen = { openAttachment(context, store, it, showMessage) },
                onRemove = viewModel::removeAttachment,
                onScan = scan,
                onPick = { picker.launch(attachmentTypes) },
            )
            if (state.reading) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.reading_receipt), style = MaterialTheme.typography.bodySmall)
                }
            } else if (permissions.editAll && state.attachments.isNotEmpty()) {
                // Viser hvilket språk forslagene er lest med, og lar brukeren lese kvitteringen på nytt som et annet.
                AssistChip(
                    onClick = { pickLanguage = true },
                    label = { Text(stringResource(R.string.read_as, stringResource(state.language.label))) },
                )
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.type == VoucherType.EXPENSE,
                    onClick = { viewModel.setType(VoucherType.EXPENSE) },
                    enabled = permissions.editAll,
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.type_expense)) }
                SegmentedButton(
                    selected = state.type == VoucherType.INCOME,
                    onClick = { viewModel.setType(VoucherType.INCOME) },
                    enabled = permissions.editAll,
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(stringResource(R.string.type_income)) }
            }

            // På en kvittering i fremmed valuta er det øverste beløpet det som står på kvitteringen, og
            // kronebeløpet får sitt eget felt under.
            val foreign = state.currency.isNotEmpty()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = if (foreign) state.foreignAmount else state.amount,
                    onValueChange = if (foreign) viewModel::setForeignAmount else viewModel::setAmount,
                    enabled = permissions.editAll,
                    label = { Text(stringResource(R.string.field_amount)) },
                    trailingIcon = {
                        TextButton(
                            onClick = { pickCurrency = true },
                            enabled = permissions.editAll,
                            contentPadding = PaddingValues(start = 8.dp, end = 4.dp),
                        ) {
                            Text(if (foreign) state.currency else stringResource(R.string.currency_nok_short))
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.currency_pick))
                        }
                    },
                    isError = if (foreign) state.foreignAmountError else state.amountError,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                if (!permissions.editAll) {
                    OutlinedTextField(
                        value = state.date.formatted(),
                        onValueChange = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.field_date)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    DateField(stringResource(R.string.field_date), state.date, viewModel::setDate, Modifier.weight(1f))
                }
            }
            if (foreign && state.foreignAmountError) {
                Text(
                    stringResource(R.string.foreign_amount_error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (foreign) {
                val amountOre = Money.parse(state.amount)
                val foreignAmount = Money.parse(state.foreignAmount)
                // Kursen kronebeløpet svarer til, slik at et beløp som er skrevet inn for hånd også kan kontrolleres.
                val effective = if (amountOre != null && foreignAmount != null) {
                    ExchangeRates.effectiveRate(amountOre, foreignAmount)?.let { ExchangeRates.describe(state.currency, it) }
                } else {
                    null
                }
                val rateText = when {
                    state.rateLoading -> stringResource(R.string.rate_loading)
                    state.rateSource.isNotEmpty() && (state.fetchedRate ?: effective) != null ->
                        stringResource(R.string.rate_with_source, state.fetchedRate ?: effective.orEmpty(), state.rateSource)
                    state.rateFailed && state.lookupsOff -> stringResource(R.string.rate_offline_setting)
                    state.rateFailed -> stringResource(R.string.rate_failed)
                    effective != null -> stringResource(R.string.rate_manual, effective)
                    else -> null
                }
                OutlinedTextField(
                    value = state.amount,
                    onValueChange = viewModel::setAmount,
                    enabled = permissions.editAll,
                    label = { Text(stringResource(R.string.field_amount_nok)) },
                    suffix = { Text(stringResource(R.string.currency_nok_short)) },
                    supportingText = rateText?.let { text -> { Text(text) } },
                    isError = state.amountError,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (permissions.editAll) {
                    Text(
                        stringResource(R.string.rate_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Kursen hentes av seg selv. Knappen er for å prøve igjen, eller gå tilbake til kursen etter å ha skrevet et beløp.
                    if (!state.rateLoading && state.rateSource.isEmpty() && foreignAmount != null && !state.lookupsOff) {
                        TextButton(onClick = viewModel::fetchRate) { Text(stringResource(R.string.rate_fetch)) }
                    }
                }
            }
            if (state.amountError) {
                Text(
                    stringResource(R.string.amount_error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            OutlinedTextField(
                value = state.counterparty,
                onValueChange = viewModel::setCounterparty,
                enabled = permissions.editAll,
                label = {
                    Text(stringResource(if (state.type == VoucherType.EXPENSE) R.string.field_supplier else R.string.field_customer))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )

            // Navnet fra Enhetsregisteret først, så linjene fra toppen av kvitteringen, så riktig firmanavn
            // kan velges med ett trykk.
            val candidates = (listOfNotNull(state.registeredName) + state.vendorCandidates).distinct()
                .filter { it != state.counterparty.trim() }
            if (permissions.editAll && candidates.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    candidates.forEach { candidate ->
                        SuggestionChip(onClick = { viewModel.setCounterparty(candidate) }, label = { Text(candidate) })
                    }
                }
            }

            // Utenlandske foretak har ikke norsk organisasjonsnummer, så feltet skjules på bilag i fremmed valuta.
            if (!foreign || state.orgNumber.isNotEmpty()) {
                val registeredName = state.registeredName
                val orgNumberNote = when {
                    registeredName != null -> stringResource(R.string.org_number_registered, registeredName)
                    state.orgNumberUnknown -> stringResource(R.string.org_number_unknown)
                    state.orgNumber.length == 9 && !OrgNumber.isValid(state.orgNumber) -> stringResource(R.string.org_number_invalid)
                    else -> null
                }
                OutlinedTextField(
                    value = state.orgNumber,
                    onValueChange = viewModel::setOrgNumber,
                    enabled = permissions.editAll,
                    label = { Text(stringResource(R.string.field_org_number)) },
                    supportingText = orgNumberNote?.let { text -> { Text(text) } },
                    isError = registeredName == null && orgNumberNote != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            CategoryField(categories, state.type, state.categoryId, permissions.editCategory, viewModel::setCategory)

            // Prosjekt er en sortering på linje med kategori, og kan derfor også settes på bilag fra en faktura.
            val projects by viewModel.projects.collectAsStateWithLifecycle()
            val projectOptions = projects.filter { it.active || it.id == state.projectId }
            if (permissions.editCategory && projectOptions.isNotEmpty()) {
                val none = stringResource(R.string.no_project)
                DropdownField(
                    label = stringResource(R.string.field_project),
                    selectedText = projects.firstOrNull { it.id == state.projectId }?.name ?: none,
                    options = listOf<Pair<Long?, String>>(null to none) + projectOptions.map { it.id to it.name },
                    onSelect = viewModel::setProject,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val trips by viewModel.trips.collectAsStateWithLifecycle()
            if (permissions.editAll && trips.isNotEmpty()) {
                val none = stringResource(R.string.no_trip)
                DropdownField(
                    label = stringResource(R.string.field_trip),
                    selectedText = trips.firstOrNull { it.id == state.tripId }?.name ?: none,
                    options = listOf<Pair<Long?, String>>(null to none) + trips.map { it.id to it.name },
                    onSelect = viewModel::setTrip,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (vatRegistered && permissions.editAll && !foreign) {
                DropdownField(
                    label = stringResource(R.string.field_vat_rate),
                    selectedText = vatRateText(state.vatRate),
                    options = vatRates.map { it to vatRateText(it) },
                    onSelect = viewModel::setVatRate,
                    modifier = Modifier.fillMaxWidth(),
                )
                val amountOre = Money.parse(state.amount)
                if (state.vatRate > 0 && amountOre != null) {
                    Text(
                        stringResource(R.string.vat_amount, Money.formatKr(Money.vatPart(amountOre, state.vatRate))),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::setDescription,
                enabled = permissions.editDescription,
                label = { Text(stringResource(R.string.field_description)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (pickCurrency) {
        CurrencyPickerDialog(
            recent = state.recentCurrencies,
            onPick = { code ->
                pickCurrency = false
                viewModel.setCurrency(code)
            },
            onDismiss = { pickCurrency = false },
        )
    }
    if (pickLanguage) {
        LanguagePickerDialog(
            models = viewModel.languageModels,
            selected = state.language,
            onSelect = { language ->
                pickLanguage = false
                viewModel.setLanguage(language)
            },
            onDismiss = { pickLanguage = false },
        )
    }
    if (confirmUnbook) {
        AlertDialog(
            onDismissRequest = { confirmUnbook = false },
            title = { Text(stringResource(R.string.unbook_title)) },
            text = { Text(stringResource(R.string.unbook_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnbook = false
                    viewModel.unbook()
                }) { Text(stringResource(R.string.unbook)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnbook = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (confirmCancel) {
        var reason by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.cancel_voucher_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.cancel_voucher_body))
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text(stringResource(R.string.cancel_reason)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    viewModel.cancel(reason)
                }) { Text(stringResource(R.string.cancel_voucher)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onClose()
                }) { Text(stringResource(R.string.discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.discard_keep)) }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_voucher_title)) },
            text = { Text(stringResource(R.string.delete_voucher_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun vatRateText(rate: Int): String =
    if (rate == 0) stringResource(R.string.vat_none) else stringResource(R.string.vat_percent, rate)

@Composable
private fun AttachmentRow(
    attachments: List<StoredFile>,
    editable: Boolean,
    store: AttachmentStore,
    onOpen: (StoredFile) -> Unit,
    onRemove: (StoredFile) -> Unit,
    onScan: () -> Unit,
    onPick: () -> Unit,
) {
    val tile = Modifier.size(width = 96.dp, height = 128.dp)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        attachments.forEach { file ->
            Box {
                AttachmentThumbnail(
                    store.file(file.fileName),
                    file.mimeType,
                    tile.clip(RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = stringResource(R.string.open_attachment)) { onOpen(file) },
                )
                if (editable) IconButton(
                    onClick = { onRemove(file) },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    ),
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clip(CircleShape),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove), Modifier.size(16.dp))
                }
            }
        }
        var menuOpen by remember { mutableStateOf(false) }
        if (editable) Box {
            OutlinedCard(onClick = { menuOpen = true }, modifier = tile) {
                Column(
                    Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(
                        stringResource(R.string.add_attachment),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_scan)) },
                    onClick = {
                        menuOpen = false
                        onScan()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_pick)) },
                    onClick = {
                        menuOpen = false
                        onPick()
                    },
                )
            }
        }
    }
}

@Composable
private fun CategoryField(
    categories: List<Category>,
    type: VoucherType,
    selectedId: Long?,
    enabled: Boolean,
    onSelect: (Long?) -> Unit,
) {
    val selected = categories.firstOrNull { it.id == selectedId }
    val none = stringResource(R.string.no_category)
    if (!enabled) {
        OutlinedTextField(
            value = selected?.name ?: none,
            onValueChange = {},
            enabled = false,
            label = { Text(stringResource(R.string.field_category)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    // En kategori som er tatt ut av bruk, vises fortsatt på bilag som allerede har den.
    val options = categories.filter { it.type == type && (it.active || it.id == selectedId) }
    DropdownField(
        label = stringResource(R.string.field_category),
        selectedText = selected?.name ?: none,
        options = listOf<Pair<Long?, String>>(null to none) + options.map { it.id to it.name },
        onSelect = onSelect,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Gir en funksjon som åpner dokumentskanneren, som retter opp og beskjærer kvitteringen. */
@Composable
private fun rememberDocumentScanner(onPages: (List<Uri>) -> Unit, onUnavailable: () -> Unit): () -> Unit {
    val activity = LocalContext.current.findActivity()
    val currentOnPages by rememberUpdatedState(onPages)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pages = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages.orEmpty()
            currentOnPages(pages.map { it.imageUri })
        }
    }
    return remember(activity, launcher) {
        {
            if (activity == null) {
                currentOnUnavailable()
            } else {
                // Uten denne sjekken viser Google sin egen feilmelding når modulen ikke er lastet ned,
                // i stedet for å si fra hit.
                val scanner = DocumentScanner.client()
                ModuleInstall.getClient(activity).areModulesAvailable(scanner)
                    .addOnSuccessListener { response ->
                        if (response.areModulesAvailable()) {
                            scanner.getStartScanIntent(activity)
                                .addOnSuccessListener { launcher.launch(IntentSenderRequest.Builder(it).build()) }
                                .addOnFailureListener { currentOnUnavailable() }
                        } else {
                            // Kameraet brukes denne gangen, mens skanneren lastes ned til neste.
                            DocumentScanner.prepare(activity)
                            currentOnUnavailable()
                        }
                    }
                    .addOnFailureListener { currentOnUnavailable() }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

private fun openAttachment(context: Context, store: AttachmentStore, file: StoredFile, onError: (String) -> Unit) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(store.uriFor(file.fileName), file.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        onError(context.getString(R.string.no_viewer))
    }
}
