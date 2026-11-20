package app.oikonomos.ui.list

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.oikonomos.R
import app.oikonomos.data.AttachmentStore
import app.oikonomos.data.ReceiptImporter
import app.oikonomos.data.VoucherType
import app.oikonomos.data.VoucherWithDetails
import app.oikonomos.domain.Money
import app.oikonomos.ocr.DocumentScanner
import app.oikonomos.ocr.ScannerStatus
import app.oikonomos.ui.AttachmentThumbnail
import app.oikonomos.ui.YearMenu
import app.oikonomos.ui.formatted
import app.oikonomos.ui.voucher.START_MANUAL
import kotlinx.coroutines.launch
import app.oikonomos.ui.voucher.START_PICK
import app.oikonomos.ui.voucher.START_SCAN

@Composable
fun VoucherListScreen(
    viewModel: VoucherListViewModel,
    store: AttachmentStore,
    importer: ReceiptImporter,
    bottomBar: @Composable () -> Unit,
    onOpen: (Long) -> Unit,
    onNew: (start: String) -> Unit,
    onSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showNewSheet by rememberSaveable { mutableStateOf(false) }
    val importing by importer.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val importPicker = rememberLauncherForActivityResult(OpenDocumentsInDownloads()) { uris -> importer.start(uris) }

    LaunchedEffect(importer) {
        importer.results.collect { result ->
            val message = if (result.failed == 0) {
                context.getString(R.string.import_done, result.imported)
            } else {
                context.getString(R.string.import_done_with_failures, result.imported, result.failed)
            }
            launch { snackbar.showSnackbar(message) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    YearMenu(state.year, state.years, viewModel::selectYear)
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewSheet = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.new_voucher)) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            // Plass til at siste rad kan rulles fri av knappen.
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            importing?.let { progress ->
                item(key = "importing") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = { progress.done.toFloat() / progress.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.importing, progress.done, progress.total),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item(key = "summary") {
                SummaryCard(state.incomeOre, state.expensesOre, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (state.totalCount > 0) {
                item(key = "search") {
                    SearchAndFilter(
                        query = viewModel.query,
                        onQueryChange = viewModel::updateQuery,
                        filter = state.filter,
                        pendingCount = state.pendingCount,
                        onFilterChange = viewModel::setFilter,
                    )
                }
            }
            items(state.items, key = { it.voucher.id }) { item ->
                VoucherRow(item, store, onClick = { onOpen(item.voucher.id) })
                HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (!state.loading && state.items.isEmpty()) {
                item(key = "empty") {
                    if (state.totalCount == 0) {
                        EmptyYear(state.year)
                    } else {
                        Text(
                            stringResource(R.string.no_matches),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }

    if (showNewSheet) {
        // Et nytt forsøk hver gang menyen åpnes, i tilfelle forrige nedlasting mislyktes.
        LaunchedEffect(Unit) { DocumentScanner.prepare(context) }
        ModalBottomSheet(onDismissRequest = { showNewSheet = false }) {
            val choose = { start: String ->
                showNewSheet = false
                onNew(start)
            }
            Column(Modifier.navigationBarsPadding()) {
                // Statusen for skanneren vises her, så det er tydelig hvorfor kameraet eventuelt brukes i stedet.
                val scanner by DocumentScanner.state.collectAsStateWithLifecycle()
                val scanHint = when (val status = scanner) {
                    ScannerStatus.Ready -> stringResource(R.string.scanner_ready)
                    ScannerStatus.Checking -> stringResource(R.string.scanner_checking)
                    is ScannerStatus.Downloading -> status.percent
                        ?.let { stringResource(R.string.scanner_downloading_percent, it) }
                        ?: stringResource(R.string.scanner_downloading)
                    is ScannerStatus.Failed -> stringResource(R.string.scanner_failed, status.code)
                }
                SheetOption(stringResource(R.string.action_scan), scanHint) { choose(START_SCAN) }
                SheetOption(stringResource(R.string.action_pick), stringResource(R.string.action_pick_hint)) { choose(START_PICK) }
                SheetOption(stringResource(R.string.action_import), stringResource(R.string.action_import_hint)) {
                    showNewSheet = false
                    importPicker.launch(arrayOf("image/*", "application/pdf"))
                }
                SheetOption(stringResource(R.string.action_manual), stringResource(R.string.action_manual_hint)) { choose(START_MANUAL) }
            }
        }
    }
}

@Composable
private fun SummaryCard(incomeOre: Long, expensesOre: Long, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryFigure(R.string.income, incomeOre, Modifier.weight(1f))
            SummaryFigure(R.string.expenses, expensesOre, Modifier.weight(1f))
            SummaryFigure(R.string.result, incomeOre - expensesOre, Modifier.weight(1f), emphasised = true)
        }
    }
}

@Composable
private fun SummaryFigure(label: Int, ore: Long, modifier: Modifier = Modifier, emphasised: Boolean = false) {
    Column(modifier) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
        Text(
            Money.format(ore),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchAndFilter(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: ListFilter,
    pendingCount: Int,
    onFilterChange: (ListFilter) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear))
                    }
                }
            },
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(filter == ListFilter.ALL, { onFilterChange(ListFilter.ALL) }, { Text(stringResource(R.string.filter_all)) })
            FilterChip(
                filter == ListFilter.EXPENSES,
                { onFilterChange(ListFilter.EXPENSES) },
                { Text(stringResource(R.string.expenses)) },
            )
            FilterChip(
                filter == ListFilter.INCOME,
                { onFilterChange(ListFilter.INCOME) },
                { Text(stringResource(R.string.income)) },
            )
            if (pendingCount > 0 || filter == ListFilter.PENDING) {
                FilterChip(
                    filter == ListFilter.PENDING,
                    { onFilterChange(ListFilter.PENDING) },
                    { Text(stringResource(R.string.filter_pending, pendingCount)) },
                )
            }
        }
    }
}

@Composable
private fun VoucherRow(item: VoucherWithDetails, store: AttachmentStore, onClick: () -> Unit) {
    val voucher = item.voucher
    val title = voucher.counterparty.ifBlank { voucher.description }.ifBlank { stringResource(R.string.unnamed) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // Statuslinjen: uavklart, annullert, eller hva som mangler på et bilag i regnskapet.
    val status: Pair<String, Color>? = when {
        voucher.isCancelled -> stringResource(R.string.status_cancelled) to muted
        voucher.isPending -> stringResource(R.string.status_pending) to MaterialTheme.colorScheme.tertiary
        else -> listOfNotNull(
            stringResource(R.string.missing_attachment).takeIf { item.attachments.isEmpty() },
            stringResource(R.string.missing_category).takeIf { item.category == null },
        ).takeIf { it.isNotEmpty() }?.let { it.joinToString(" · ") to MaterialTheme.colorScheme.error }
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            val thumbnail = Modifier.size(width = 44.dp, height = 56.dp).clip(RoundedCornerShape(6.dp))
            val first = item.sortedAttachments.firstOrNull()
            if (first != null) {
                AttachmentThumbnail(store.file(first.fileName), first.mimeType, thumbnail)
            } else {
                Spacer(thumbnail)
            }
        },
        headlineContent = {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (voucher.isCancelled) muted else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(
                        voucher.date.formatted(),
                        // Beskrivelsen skiller for eksempel en faktura fra kreditnotaen til samme kunde.
                        voucher.description.takeIf { it.isNotBlank() && voucher.counterparty.isNotBlank() },
                        item.category?.name,
                        item.trip?.name,
                    ).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (status != null) {
                    Text(status.first, color = status.second, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        trailingContent = {
            val income = voucher.type == VoucherType.INCOME
            // Beløpet på en uavklart kvittering kan stå tomt.
            if (voucher.amountOre != 0L) {
                Text(
                    // En kreditnota er en negativ inntekt og har allerede minustegn.
                    (if (income && voucher.amountOre > 0) "+" else "") + Money.format(voucher.amountOre),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (voucher.isCancelled) TextDecoration.LineThrough else null,
                    color = when {
                        !voucher.counts -> muted
                        income -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            } else if (voucher.currency.isNotEmpty() && voucher.foreignAmount != 0L) {
                // Kronebeløpet mangler til kursen er hentet. Imens vises beløpet slik det står på kvitteringen.
                Text("${Money.format(voucher.foreignAmount)} ${voucher.currency}", style = MaterialTheme.typography.bodyLarge, color = muted)
            }
        },
    )
}

@Composable
private fun EmptyYear(year: Int) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.empty_title, year), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_body),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SheetOption(title: String, hint: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = { Text(hint) },
    )
}

/**
 * Filvelgeren for masseimport åpnes i Nedlastinger, der nettbanken og nettleseren legger filene.
 * Brukeren kan bla til en annen mappe derfra.
 */
private class OpenDocumentsInDownloads : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildDocumentUri("com.android.providers.downloads.documents", "downloads"),
        )
}
