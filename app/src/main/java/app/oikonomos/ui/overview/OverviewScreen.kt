package app.oikonomos.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.oikonomos.AppContainer
import app.oikonomos.R
import app.oikonomos.data.VoucherType
import app.oikonomos.domain.CategorySum
import app.oikonomos.domain.Money
import app.oikonomos.domain.Overview
import app.oikonomos.domain.ProjectSum
import app.oikonomos.domain.VatTerm
import app.oikonomos.ui.YearMenu
import app.oikonomos.ui.invoice.invoiceListState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

data class OverviewUiState(
    val loading: Boolean = true,
    val year: Int,
    val years: List<Int> = listOf(year),
    val voucherCount: Int = 0,
    val income: List<CategorySum> = emptyList(),
    val expenses: List<CategorySum> = emptyList(),
    val projects: List<ProjectSum> = emptyList(),
    val vatRegistered: Boolean = false,
    val vatTerms: List<VatTerm> = emptyList(),
    val outstandingOre: Long = 0,
    val overdueOre: Long = 0,
    val missingAttachment: Int = 0,
    val missingCategory: Int = 0,
    /** Kvitteringer i året som ikke er ført i regnskapet ennå. */
    val pending: Int = 0,
) {
    val incomeOre: Long get() = income.sumOf { it.sumOre }
    val expensesOre: Long get() = expenses.sumOf { it.sumOre }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OverviewViewModel(container: AppContainer) : ViewModel() {
    private val currentYear = LocalDate.now().year
    private val selectedYear = MutableStateFlow(currentYear)

    val state: StateFlow<OverviewUiState> = combine(
        selectedYear,
        container.repository.observeYears(),
        selectedYear.flatMapLatest(container.repository::observeYear),
        container.invoices.observeInvoices(),
        container.settings.settings,
    ) { year, years, allVouchers, invoices, settings ->
        // Uavklarte kvitteringer og annullerte bilag vises i bilagslisten, men teller ikke i regnskapet.
        val vouchers = allVouchers.filter { it.voucher.counts }
        // Utestående fakturaer gjelder nå, uavhengig av hvilket år som vises.
        val invoiceState = invoiceListState(invoices, LocalDate.now())
        OverviewUiState(
            loading = false,
            year = year,
            years = (years + currentYear + year).distinct().sortedDescending(),
            voucherCount = vouchers.size,
            income = Overview.byCategory(vouchers, VoucherType.INCOME),
            expenses = Overview.byCategory(vouchers, VoucherType.EXPENSE),
            projects = Overview.byProject(vouchers),
            vatRegistered = settings.vatRegistered,
            vatTerms = Overview.vatTerms(vouchers),
            outstandingOre = invoiceState.outstandingOre,
            overdueOre = invoiceState.overdueOre,
            missingAttachment = vouchers.count { it.attachments.isEmpty() },
            missingCategory = vouchers.count { it.category == null },
            pending = allVouchers.count { it.voucher.isPending },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverviewUiState(year = currentYear))

    fun selectYear(year: Int) {
        selectedYear.value = year
    }
}

@Composable
fun OverviewScreen(viewModel: OverviewViewModel, bottomBar: @Composable () -> Unit, onSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_overview)) },
                actions = {
                    YearMenu(state.year, state.years, viewModel::selectYear)
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        if (state.loading) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AmountRow(stringResource(R.string.income), state.incomeOre)
                    AmountRow(stringResource(R.string.expenses), state.expensesOre)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    AmountRow(stringResource(R.string.result), state.incomeOre - state.expensesOre, emphasised = true)
                }
            }

            if (state.vatRegistered) {
                Text(
                    stringResource(R.string.overview_ex_vat),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.voucherCount == 0) {
                Text(stringResource(R.string.overview_empty, state.year), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.income.isNotEmpty()) CategorySection(R.string.income, state.income)
            if (state.expenses.isNotEmpty()) CategorySection(R.string.expenses, state.expenses)
            if (state.projects.isNotEmpty()) ProjectSection(state.projects)
            if (state.vatRegistered) VatSection(state.vatTerms)

            Section(R.string.overview_invoices) {
                if (state.outstandingOre == 0L) {
                    Text(stringResource(R.string.overview_invoices_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    AmountRow(stringResource(R.string.outstanding), state.outstandingOre)
                    if (state.overdueOre > 0) {
                        Text(
                            stringResource(R.string.overview_overdue, Money.formatKr(state.overdueOre)),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (state.voucherCount > 0 || state.pending > 0) {
                Section(R.string.overview_cleanup) {
                    if (state.pending > 0) Text(stringResource(R.string.overview_pending, state.pending))
                    if (state.missingAttachment == 0 && state.missingCategory == 0 && state.pending == 0) {
                        Text(stringResource(R.string.overview_tidy), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.missingAttachment > 0) Text(stringResource(R.string.overview_missing_attachment, state.missingAttachment))
                    if (state.missingCategory > 0) Text(stringResource(R.string.overview_missing_category, state.missingCategory))
                }
            }
        }
    }
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun CategorySection(title: Int, sums: List<CategorySum>) {
    Section(title) {
        sums.forEach { sum ->
            val name = sum.name.ifEmpty { stringResource(R.string.overview_no_category) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(sum.account, stringResource(R.string.overview_count, sum.count))
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(Money.format(sum.sumOre))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AmountRow(stringResource(title), sums.sumOf { it.sumOre }, emphasised = true)
    }
}

@Composable
private fun ProjectSection(projects: List<ProjectSum>) {
    Section(R.string.projects) {
        projects.forEach { project ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.project_figures, Money.format(project.incomeOre), Money.format(project.expensesOre)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(Money.format(project.resultOre))
            }
        }
    }
}

@Composable
private fun VatSection(terms: List<VatTerm>) {
    Section(R.string.overview_vat) {
        Row(Modifier.fillMaxWidth()) {
            Text("", Modifier.weight(1.2f))
            listOf(R.string.overview_vat_outgoing, R.string.overview_vat_incoming, R.string.overview_vat_payable).forEach {
                Text(
                    stringResource(it),
                    Modifier.weight(1f),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        terms.forEach { term ->
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.overview_vat_term, term.term), Modifier.weight(1.2f))
                listOf(term.outgoingOre, term.incomingOre, term.payableOre).forEach {
                    Text(Money.format(it), Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun AmountRow(label: String, ore: Long, emphasised: Boolean = false) {
    val weight = if (emphasised) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = weight)
        Text(Money.format(ore), fontWeight = weight)
    }
}
