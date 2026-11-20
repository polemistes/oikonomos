package app.oikonomos.ui.list

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.oikonomos.data.VoucherRepository
import app.oikonomos.data.VoucherType
import app.oikonomos.data.VoucherWithDetails
import app.oikonomos.domain.Money
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

enum class ListFilter { ALL, EXPENSES, INCOME, PENDING }

data class ListUiState(
    val loading: Boolean = true,
    val year: Int,
    val years: List<Int> = listOf(year),
    val filter: ListFilter = ListFilter.ALL,
    /** Summene er uten MVA, slik de inngår i resultatet. */
    val incomeOre: Long = 0,
    val expensesOre: Long = 0,
    /** Antall bilag og kvitteringer i året, før søk og filter. */
    val totalCount: Int = 0,
    /** Kvitteringer i året som ikke er ført i regnskapet ennå. */
    val pendingCount: Int = 0,
    val items: List<VoucherWithDetails> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class VoucherListViewModel(repository: VoucherRepository) : ViewModel() {
    private val currentYear = LocalDate.now().year
    private val selectedYear = MutableStateFlow(currentYear)
    private val filter = MutableStateFlow(ListFilter.ALL)

    /** Søketeksten holdes som Compose-tilstand, slik at tekstfeltet oppdateres uten forsinkelse. */
    var query by mutableStateOf("")
        private set

    val state: StateFlow<ListUiState> = combine(
        selectedYear,
        repository.observeYears(),
        selectedYear.flatMapLatest(repository::observeYear),
        snapshotFlow { query },
        filter,
    ) { year, years, vouchers, query, filter ->
        val counted = vouchers.filter { it.voucher.counts }
        ListUiState(
            loading = false,
            year = year,
            years = (years + currentYear + year).distinct().sortedDescending(),
            filter = filter,
            incomeOre = counted.filter { it.voucher.type == VoucherType.INCOME }.sumOf { it.voucher.netOre },
            expensesOre = counted.filter { it.voucher.type == VoucherType.EXPENSE }.sumOf { it.voucher.netOre },
            totalCount = vouchers.size,
            pendingCount = vouchers.count { it.voucher.isPending },
            items = vouchers.filter { it.matches(filter) && it.matches(query) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListUiState(year = currentYear))

    fun selectYear(year: Int) {
        selectedYear.value = year
    }

    fun setFilter(value: ListFilter) {
        filter.value = value
    }

    private fun VoucherWithDetails.matches(filter: ListFilter): Boolean = when (filter) {
        ListFilter.ALL -> true
        ListFilter.EXPENSES -> voucher.type == VoucherType.EXPENSE
        ListFilter.INCOME -> voucher.type == VoucherType.INCOME
        ListFilter.PENDING -> voucher.isPending
    }

    fun updateQuery(text: String) {
        query = text
    }

    private fun VoucherWithDetails.matches(query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return listOf(
            voucher.counterparty,
            voucher.description,
            voucher.label,
            category?.name.orEmpty(),
            Money.formatPlain(voucher.amountOre),
        ).any { it.contains(needle, ignoreCase = true) }
    }
}
