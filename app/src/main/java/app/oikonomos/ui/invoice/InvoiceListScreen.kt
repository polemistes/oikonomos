package app.oikonomos.ui.invoice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.oikonomos.R
import app.oikonomos.data.InvoiceListItem
import app.oikonomos.data.InvoiceRepository
import app.oikonomos.domain.InvoiceCalc
import app.oikonomos.domain.InvoiceStatus
import app.oikonomos.domain.Money
import app.oikonomos.ui.color
import app.oikonomos.ui.formatted
import app.oikonomos.ui.label
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

data class InvoiceRow(val item: InvoiceListItem, val status: InvoiceStatus)

data class InvoiceListUiState(
    val loading: Boolean = true,
    val rows: List<InvoiceRow> = emptyList(),
    /** Utstedte fakturaer som ikke er betalt, medregnet de forfalte. */
    val outstandingOre: Long = 0,
    val overdueOre: Long = 0,
)

fun invoiceListState(items: List<InvoiceListItem>, today: LocalDate): InvoiceListUiState {
    val rows = items.map { InvoiceRow(it, InvoiceCalc.status(it.invoice, it.credited, today)) }
    val overdue = rows.filter { it.status == InvoiceStatus.OVERDUE }.sumOf { it.item.invoice.totalOre }
    val unpaid = rows.filter { it.status == InvoiceStatus.UNPAID }.sumOf { it.item.invoice.totalOre }
    return InvoiceListUiState(loading = false, rows = rows, outstandingOre = unpaid + overdue, overdueOre = overdue)
}

class InvoiceListViewModel(repository: InvoiceRepository) : ViewModel() {
    val state: StateFlow<InvoiceListUiState> = repository.observeInvoices()
        .map { invoiceListState(it, LocalDate.now()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InvoiceListUiState())
}

@Composable
fun InvoiceListScreen(
    viewModel: InvoiceListViewModel,
    bottomBar: @Composable () -> Unit,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    onCustomers: () -> Unit,
    onSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_invoices)) },
                actions = {
                    IconButton(onClick = onCustomers) {
                        Icon(Icons.Filled.Person, contentDescription = stringResource(R.string.customers))
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.invoice_new)) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            item(key = "summary") {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Figure(R.string.outstanding, state.outstandingOre, Modifier.weight(1f))
                        Figure(R.string.overdue, state.overdueOre, Modifier.weight(1f))
                    }
                }
            }
            items(state.rows, key = { it.item.invoice.id }) { row ->
                InvoiceListRow(row, onClick = { onOpen(row.item.invoice.id) })
                HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (!state.loading && state.rows.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.invoices_empty_title), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.invoices_empty_body),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Figure(label: Int, ore: Long, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
        Text(Money.format(ore), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InvoiceListRow(row: InvoiceRow, onClick: () -> Unit) {
    val invoice = row.item.invoice
    val number = invoice.number
    val title = when {
        number == null -> stringResource(R.string.status_draft)
        invoice.isCreditNote -> stringResource(R.string.credit_note_title, number)
        else -> stringResource(R.string.invoice_title, number)
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(row.item.customerName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("$title · ${invoice.issueDate.formatted()}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(Money.format(invoice.totalOre), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(row.status.label(), style = MaterialTheme.typography.labelMedium, color = row.status.color())
            }
        },
    )
}
