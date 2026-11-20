package app.oikonomos.ui.invoice

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.oikonomos.R
import app.oikonomos.data.Customer
import app.oikonomos.data.CustomerType
import app.oikonomos.data.InvoiceLine
import app.oikonomos.domain.InvoiceCalc
import app.oikonomos.domain.InvoiceStatus
import app.oikonomos.domain.InvoiceTotals
import app.oikonomos.domain.Money
import app.oikonomos.ui.DateField
import app.oikonomos.ui.DatePickerPopup
import app.oikonomos.ui.DropdownField
import app.oikonomos.ui.FormDialog
import app.oikonomos.ui.color
import app.oikonomos.ui.formatted
import app.oikonomos.ui.label
import kotlinx.coroutines.launch
import java.time.LocalDate

private val vatRates = listOf(25, 15, 12, 0)

/** Valget «Ny kunde …» i kundelisten. */
private const val NEW_CUSTOMER = -1L

@Composable
fun InvoiceScreen(viewModel: InvoiceViewModel, onClose: () -> Unit, onSettings: () -> Unit) {
    val state = viewModel.state
    val customers by viewModel.customers.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val currentOnClose by rememberUpdatedState(onClose)
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var sellerIncomplete by rememberSaveable { mutableStateOf(false) }
    val showMessage: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                InvoiceEvent.Closed -> currentOnClose()
                InvoiceEvent.SellerIncomplete -> sellerIncomplete = true
                is InvoiceEvent.Message -> launch {
                    snackbar.showSnackbar(context.getString(event.message.id, *event.message.args.toTypedArray()))
                }
            }
        }
    }

    val editing = !state.isIssued
    val leave = { if (editing && state.dirty) confirmDiscard = true else onClose() }
    BackHandler(enabled = editing && state.dirty) { confirmDiscard = true }

    val saved = state.saved
    val number = saved?.number
    val title = when {
        saved == null -> stringResource(R.string.invoice_new)
        number == null -> stringResource(R.string.invoice_draft)
        saved.isCreditNote -> stringResource(R.string.credit_note_title, number)
        else -> stringResource(R.string.invoice_title, number)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (saved != null && saved.isDraft) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val totals = InvoiceCalc.totals(state.lines, settings.vatRegistered)
            if (editing) {
                DraftEditor(viewModel, state, customers, settings.vatRegistered, totals)
            } else {
                IssuedInvoice(
                    viewModel = viewModel,
                    state = state,
                    vatRegistered = settings.vatRegistered,
                    totals = totals,
                    onOpenPdf = {
                        scope.launch {
                            val uri = viewModel.pdfUri()
                            if (uri == null) showMessage(context.getString(R.string.pdf_missing)) else openPdf(context, uri, showMessage)
                        }
                    },
                    onSharePdf = {
                        scope.launch {
                            val uri = viewModel.shareablePdf()
                            if (uri == null || saved == null || number == null) {
                                showMessage(context.getString(R.string.pdf_missing))
                            } else {
                                val subject = context.getString(
                                    if (saved.isCreditNote) R.string.share_subject_credit else R.string.share_subject,
                                    number,
                                    settings.companyName,
                                )
                                sharePdf(context, uri, subject)
                            }
                        }
                    },
                )
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = R.string.discard_title,
            body = null,
            confirm = R.string.discard_confirm,
            dismiss = R.string.discard_keep,
            onConfirm = onClose,
            onDismiss = { confirmDiscard = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = R.string.delete_draft_title,
            body = null,
            confirm = R.string.delete,
            dismiss = R.string.cancel,
            onConfirm = viewModel::deleteDraft,
            onDismiss = { confirmDelete = false },
        )
    }
    if (sellerIncomplete) {
        ConfirmDialog(
            title = null,
            body = R.string.issue_missing_seller,
            confirm = R.string.open_settings,
            dismiss = R.string.cancel,
            onConfirm = onSettings,
            onDismiss = { sellerIncomplete = false },
        )
    }
}

@Composable
private fun DraftEditor(
    viewModel: InvoiceViewModel,
    state: InvoiceUiState,
    customers: List<Customer>,
    vatRegistered: Boolean,
    totals: InvoiceTotals,
) {
    var newCustomer by remember { mutableStateOf(false) }
    // Indeksen til linjen som redigeres, -1 for en ny linje, null når dialogen er lukket.
    var editingLine by remember { mutableStateOf<Int?>(null) }
    var confirmIssue by rememberSaveable { mutableStateOf(false) }
    val selected = customers.firstOrNull { it.id == state.customerId }

    DropdownField(
        label = stringResource(R.string.field_customer_pick),
        selectedText = selected?.name ?: stringResource(R.string.customer_none),
        options = customers.map { it.id to it.name } + (NEW_CUSTOMER to stringResource(R.string.customer_new_option)),
        onSelect = { id ->
            if (id == NEW_CUSTOMER) newCustomer = true else customers.firstOrNull { it.id == id }?.let(viewModel::setCustomer)
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (selected?.type == CustomerType.BUSINESS) EInvoiceNotice()

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DateField(stringResource(R.string.field_issue_date), state.issueDate, viewModel::setIssueDate, Modifier.weight(1f))
        DateField(stringResource(R.string.field_due_date), state.dueDate, viewModel::setDueDate, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DateField(stringResource(R.string.field_delivery_date), state.deliveryDate, viewModel::setDeliveryDate, Modifier.weight(1f))
        OutlinedTextField(
            value = state.deliveryPlace,
            onValueChange = viewModel::setDeliveryPlace,
            label = { Text(stringResource(R.string.field_delivery_place)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.weight(1f),
        )
    }
    OutlinedTextField(
        value = state.customerReference,
        onValueChange = viewModel::setCustomerReference,
        label = { Text(stringResource(R.string.field_customer_reference)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Column {
        Text(stringResource(R.string.invoice_lines), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        state.lines.forEachIndexed { index, line ->
            LineRow(line, vatRegistered, Modifier.clickable { editingLine = index })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        TextButton(onClick = { editingLine = -1 }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.line_add), Modifier.padding(start = 8.dp))
        }
    }
    if (state.lines.isNotEmpty()) TotalsBlock(totals, vatRegistered)

    OutlinedTextField(
        value = state.note,
        onValueChange = viewModel::setNote,
        label = { Text(stringResource(R.string.field_invoice_note)) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = viewModel::saveDraft, enabled = !state.busy, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.save_draft))
        }
        Button(onClick = { confirmIssue = true }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.issue))
        }
    }

    if (newCustomer) {
        CustomerDialog(
            customer = Customer(name = ""),
            onDismiss = { newCustomer = false },
            onSave = {
                newCustomer = false
                viewModel.addCustomer(it)
            },
            onDelete = null,
            lookUp = viewModel::lookUpCompany,
        )
    }
    editingLine?.let { index ->
        val existing = state.lines.getOrNull(index)
        LineDialog(
            line = existing,
            vatRegistered = vatRegistered,
            onDismiss = { editingLine = null },
            onSave = {
                editingLine = null
                viewModel.putLine(index.takeIf { existing != null }, it)
            },
            onDelete = {
                editingLine = null
                viewModel.removeLine(index)
            },
        )
    }
    if (confirmIssue) {
        ConfirmDialog(
            title = R.string.issue_title,
            body = R.string.issue_body,
            confirm = R.string.issue_confirm,
            dismiss = R.string.cancel,
            onConfirm = viewModel::issue,
            onDismiss = { confirmIssue = false },
        )
    }
}

@Composable
private fun IssuedInvoice(
    viewModel: InvoiceViewModel,
    state: InvoiceUiState,
    vatRegistered: Boolean,
    totals: InvoiceTotals,
    onOpenPdf: () -> Unit,
    onSharePdf: () -> Unit,
) {
    val invoice = state.saved ?: return
    var pickPaidDate by remember { mutableStateOf(false) }
    var confirmCredit by rememberSaveable { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.customer?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
            Text(Money.formatKr(invoice.totalOre), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(state.status.label(), color = state.status.color(), style = MaterialTheme.typography.labelLarge)
            Text("${stringResource(R.string.field_issue_date)} ${invoice.issueDate.formatted()}")
            val creditedNumber = state.creditedInvoice?.number
            val paidDate = invoice.paidDate
            when {
                creditedNumber != null -> Text(stringResource(R.string.credit_for, creditedNumber))
                paidDate != null -> Text(stringResource(R.string.paid_on, paidDate.formatted()))
                else -> Text(stringResource(R.string.due_on, invoice.dueDate.formatted()))
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onSharePdf, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.pdf_share)) }
        OutlinedButton(onClick = onOpenPdf, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.pdf_open)) }
    }
    if (state.customer?.type == CustomerType.BUSINESS) EInvoiceNotice()

    when (state.status) {
        InvoiceStatus.UNPAID, InvoiceStatus.OVERDUE ->
            OutlinedButton(onClick = { pickPaidDate = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.mark_paid)) }
        InvoiceStatus.PAID ->
            TextButton(onClick = { viewModel.setPaid(null) }) { Text(stringResource(R.string.mark_unpaid)) }
        else -> Unit
    }

    Column {
        Text(stringResource(R.string.invoice_lines), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        state.lines.forEach { line ->
            LineRow(line, vatRegistered)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    TotalsBlock(totals, vatRegistered)

    if (state.status in listOf(InvoiceStatus.UNPAID, InvoiceStatus.OVERDUE, InvoiceStatus.PAID)) {
        TextButton(onClick = { confirmCredit = true }, enabled = !state.busy) { Text(stringResource(R.string.credit)) }
    }

    if (pickPaidDate) {
        DatePickerPopup(LocalDate.now(), onPick = { viewModel.setPaid(it) }, onDismiss = { pickPaidDate = false })
    }
    if (confirmCredit) {
        ConfirmDialog(
            title = R.string.credit_title,
            body = R.string.credit_body,
            confirm = R.string.credit,
            dismiss = R.string.cancel,
            onConfirm = viewModel::credit,
            onDismiss = { confirmCredit = false },
        )
    }
}

@Composable
private fun EInvoiceNotice() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Text(
            stringResource(R.string.einvoice_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun LineRow(line: InvoiceLine, vatRegistered: Boolean, modifier: Modifier = Modifier) {
    val details = stringResource(
        R.string.line_summary,
        InvoiceCalc.formatQuantity(line.quantityMilli),
        Money.format(line.unitPriceOre),
    ) + if (vatRegistered) " · ${stringResource(R.string.total_vat)} ${line.vatRate} %" else ""
    ListItem(
        modifier = modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(line.description) },
        supportingContent = { Text(details) },
        trailingContent = { Text(Money.format(InvoiceCalc.lineNet(line)), style = MaterialTheme.typography.bodyLarge) },
    )
}

@Composable
private fun TotalsBlock(totals: InvoiceTotals, vatRegistered: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (vatRegistered) {
            TotalRow(stringResource(R.string.total_net), Money.format(totals.netOre))
            totals.vatGroups.forEach { group ->
                TotalRow("${stringResource(R.string.total_vat)} ${group.rate} %", Money.format(group.vatOre))
            }
        }
        TotalRow(stringResource(R.string.total_to_pay), Money.formatKr(totals.grossOre), emphasised = true)
    }
}

@Composable
private fun TotalRow(label: String, value: String, emphasised: Boolean = false) {
    val style = if (emphasised) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = style)
        Text(value, style = style, fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun LineDialog(
    line: InvoiceLine?,
    vatRegistered: Boolean,
    onDismiss: () -> Unit,
    onSave: (InvoiceLine) -> Unit,
    onDelete: () -> Unit,
) {
    var description by remember { mutableStateOf(line?.description.orEmpty()) }
    var quantity by remember { mutableStateOf(line?.let { InvoiceCalc.formatQuantity(it.quantityMilli) } ?: "1") }
    var price by remember { mutableStateOf(line?.let { Money.formatPlain(it.unitPriceOre) }.orEmpty()) }
    var vatRate by remember { mutableStateOf(line?.vatRate ?: if (vatRegistered) 25 else 0) }
    var showError by remember { mutableStateOf(false) }

    FormDialog(
        title = stringResource(if (line == null) R.string.line_add else R.string.line_edit),
        onDismiss = onDismiss,
        onSave = {
            val quantityMilli = InvoiceCalc.parseQuantity(quantity)
            val priceOre = Money.parse(price)
            if (description.isBlank() || quantityMilli == null || priceOre == null) {
                showError = true
            } else {
                onSave(
                    InvoiceLine(
                        description = description.trim(),
                        quantityMilli = quantityMilli,
                        unitPriceOre = priceOre,
                        vatRate = if (vatRegistered) vatRate else 0,
                    ),
                )
            }
        },
        onDelete = onDelete.takeIf { line != null },
    ) {
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text(stringResource(R.string.line_description)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = quantity,
                onValueChange = { quantity = it },
                label = { Text(stringResource(R.string.line_quantity)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = price,
                onValueChange = { price = it },
                label = {
                    Text(stringResource(if (vatRegistered) R.string.line_unit_price_ex_vat else R.string.line_unit_price))
                },
                suffix = { Text("kr") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(3f),
            )
        }
        if (vatRegistered) {
            DropdownField(
                label = stringResource(R.string.field_vat_rate),
                selectedText = stringResource(R.string.vat_percent, vatRate),
                options = vatRates.map { it to stringResource(R.string.vat_percent, it) },
                onSelect = { vatRate = it },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (showError) {
            Text(
                stringResource(R.string.line_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ConfirmDialog(title: Int?, body: Int?, confirm: Int, dismiss: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title?.let { { Text(stringResource(it)) } },
        text = body?.let { { Text(stringResource(it)) } },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(stringResource(confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(dismiss)) } },
    )
}

private fun openPdf(context: Context, uri: Uri, onError: (String) -> Unit) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        onError(context.getString(R.string.no_viewer))
    }
}

/** Åpner delingsmenyen, så brukeren selv velger e-post eller en annen app å sende fakturaen med. */
private fun sharePdf(context: Context, uri: Uri, subject: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // Uten ClipData får ikke delingsmenyens forhåndsvisning lesetilgang til filen.
    intent.clipData = ClipData.newRawUri(null, uri)
    context.startActivity(Intent.createChooser(intent, null))
}
