package app.oikonomos.ui.trip

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.oikonomos.R
import app.oikonomos.data.AttachmentStore
import app.oikonomos.data.Project
import app.oikonomos.data.Trip
import app.oikonomos.data.VoucherRepository
import app.oikonomos.data.VoucherWithDetails
import app.oikonomos.domain.Money
import app.oikonomos.ui.AttachmentThumbnail
import app.oikonomos.ui.formatted
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class TripUiState(
    val loading: Boolean = true,
    /** Null når reisen ikke finnes lenger. */
    val trip: Trip? = null,
    val onTrip: List<VoucherWithDetails> = emptyList(),
    /** Bilag og kvitteringer uten reise som er datert i perioden. */
    val candidates: List<VoucherWithDetails> = emptyList(),
    /** Bilag uten reise fra før og etter perioden, nærmest reisen først. */
    val outside: List<VoucherWithDetails> = emptyList(),
    val totals: TripTotals = TripTotals(0, 0, 0),
)

@OptIn(ExperimentalCoroutinesApi::class)
class TripViewModel(private val tripId: Long, private val repository: VoucherRepository) : ViewModel() {
    val state: StateFlow<TripUiState> = repository.observeTrip(tripId).flatMapLatest { trip ->
        if (trip == null) {
            flowOf(TripUiState(loading = false))
        } else {
            repository.observeTripVouchers(trip).map { vouchers ->
                val (onTrip, unassigned) = vouchers.partition { it.voucher.tripId == trip.id }
                val (inPeriod, outside) = unassigned.partition { it.voucher.date in trip.fromDate..trip.toDate }
                TripUiState(
                    loading = false,
                    trip = trip,
                    onTrip = onTrip,
                    candidates = inPeriod,
                    outside = outside.sortedBy { daysFromTrip(trip, it.voucher.date) },
                    totals = tripTotals(onTrip.map { it.voucher }),
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripUiState())

    private fun daysFromTrip(trip: Trip, date: LocalDate): Long =
        if (date < trip.fromDate) ChronoUnit.DAYS.between(date, trip.fromDate) else ChronoUnit.DAYS.between(trip.toDate, date)

    val projects: StateFlow<List<Project>> = repository.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(voucherId: Long) {
        viewModelScope.launch { repository.addToTrip(voucherId, tripId) }
    }

    fun remove(voucherId: Long) {
        viewModelScope.launch { repository.removeFromTrip(voucherId) }
    }

    /** Sletter en uavklart kvittering som ikke skal med i regnskapet. */
    fun deleteReceipt(voucherId: Long) {
        viewModelScope.launch { repository.delete(voucherId) }
    }

    fun save(trip: Trip) {
        viewModelScope.launch { repository.saveTrip(trip) }
    }

    fun deleteTrip() {
        viewModelScope.launch { repository.deleteTrip(tripId) }
    }
}

@Composable
fun TripScreen(viewModel: TripViewModel, store: AttachmentStore, onBack: () -> Unit, onOpenVoucher: (Long) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnBack by rememberUpdatedState(onBack)
    var editing by remember { mutableStateOf(false) }
    var confirmDeleteTrip by rememberSaveable { mutableStateOf(false) }
    var deleteReceiptId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showOutside by rememberSaveable { mutableStateOf(false) }
    val trip = state.trip
    val projects by viewModel.projects.collectAsStateWithLifecycle()

    // Reisen er slettet, her eller ved gjenoppretting, så det er ingenting å vise.
    LaunchedEffect(state.loading, trip == null) {
        if (!state.loading && trip == null) currentOnBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(trip?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { editing = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.trip_edit))
                    }
                    IconButton(onClick = { confirmDeleteTrip = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                    }
                },
            )
        },
    ) { padding ->
        if (trip == null) return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "summary") {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${trip.fromDate.formatted()} – ${trip.toDate.formatted()}", style = MaterialTheme.typography.titleMedium)
                        projects.firstOrNull { it.id == trip.projectId }?.let {
                            Text(stringResource(R.string.trip_project, it.name), style = MaterialTheme.typography.labelLarge)
                        }
                        if (trip.note.isNotBlank()) Text(trip.note)
                        Text(stringResource(R.string.trip_costs), style = MaterialTheme.typography.labelMedium)
                        Text(Money.formatKr(state.totals.costOre), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            item(key = "on-title") { SectionTitle(stringResource(R.string.trip_on), state.onTrip.size) }
            if (state.onTrip.isEmpty()) item(key = "on-empty") { Hint(stringResource(R.string.trip_on_empty)) }
            items(state.onTrip, key = { "on-${it.voucher.id}" }) { item ->
                TripVoucherRow(item, store, onClick = { onOpenVoucher(item.voucher.id) }) {
                    TextButton(onClick = { viewModel.remove(item.voucher.id) }) { Text(stringResource(R.string.trip_remove)) }
                }
                HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }

            item(key = "candidates-title") { SectionTitle(stringResource(R.string.trip_candidates), state.candidates.size) }
            item(key = "candidates-hint") {
                Hint(stringResource(if (state.candidates.isEmpty()) R.string.trip_candidates_empty else R.string.trip_candidates_hint))
            }
            items(state.candidates, key = { "candidate-${it.voucher.id}" }) { item ->
                CandidateRow(item, store, onOpenVoucher, onAdd = viewModel::add, onDelete = { deleteReceiptId = it })
            }

            // Billetter og overnatting betales ofte lenge før reisen, og noe kommer etterpå.
            item(key = "outside-title") { SectionTitle(stringResource(R.string.trip_outside), state.outside.size) }
            item(key = "outside-hint") {
                Hint(stringResource(if (state.outside.isEmpty()) R.string.trip_outside_empty else R.string.trip_outside_hint))
            }
            if (state.outside.isNotEmpty() && !showOutside) {
                item(key = "outside-show") {
                    TextButton(onClick = { showOutside = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.trip_outside_show, state.outside.size))
                    }
                }
            }
            if (showOutside) {
                items(state.outside, key = { "outside-${it.voucher.id}" }) { item ->
                    CandidateRow(item, store, onOpenVoucher, onAdd = viewModel::add, onDelete = { deleteReceiptId = it })
                }
            }
        }
    }

    if (editing && trip != null) {
        TripDialog(
            trip = trip,
            projects = projects,
            onDismiss = { editing = false },
            onSave = {
                editing = false
                viewModel.save(it)
            },
        )
    }
    if (confirmDeleteTrip) {
        AlertDialog(
            onDismissRequest = { confirmDeleteTrip = false },
            title = { Text(stringResource(R.string.trip_delete_title)) },
            text = { Text(stringResource(R.string.trip_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteTrip = false
                    viewModel.deleteTrip()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteTrip = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleteReceiptId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteReceiptId = null },
            title = { Text(stringResource(R.string.delete_voucher_title)) },
            text = { Text(stringResource(R.string.delete_voucher_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteReceiptId = null
                    viewModel.deleteReceipt(id)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteReceiptId = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SectionTitle(title: String, count: Int) {
    Text(
        "$title ($count)",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Et bilag uten reise, som enten kan legges til reisen eller, hvis det er en uavklart kvittering, slettes. */
@Composable
private fun CandidateRow(
    item: VoucherWithDetails,
    store: AttachmentStore,
    onOpen: (Long) -> Unit,
    onAdd: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    TripVoucherRow(item, store, onClick = { onOpen(item.voucher.id) }) {
        // Det som alt er ført i regnskapet, kan ikke slettes herfra.
        if (item.voucher.isPending) {
            TextButton(onClick = { onDelete(item.voucher.id) }) { Text(stringResource(R.string.delete)) }
        }
        FilledTonalButton(onClick = { onAdd(item.voucher.id) }) { Text(stringResource(R.string.trip_add)) }
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** En bilagsrad med knapper under, for valgene som gjelder på reisesiden. */
@Composable
private fun TripVoucherRow(
    item: VoucherWithDetails,
    store: AttachmentStore,
    onClick: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val voucher = item.voucher
    val title = voucher.counterparty.ifBlank { voucher.description }.ifBlank { stringResource(R.string.unnamed) }
    Column(Modifier.clickable(onClick = onClick).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val thumbnail = Modifier.size(width = 44.dp, height = 56.dp).clip(RoundedCornerShape(6.dp))
            val first = item.sortedAttachments.firstOrNull()
            if (first != null) AttachmentThumbnail(store.file(first.fileName), first.mimeType, thumbnail) else Spacer(thumbnail)
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(voucher.date.formatted(), item.category?.name).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (voucher.isPending) {
                    Text(
                        stringResource(R.string.status_pending),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (voucher.amountOre != 0L) {
                Text(Money.format(voucher.amountOre), fontWeight = FontWeight.Medium, modifier = Modifier.padding(end = 8.dp))
            } else if (voucher.currency.isNotEmpty() && voucher.foreignAmount != 0L) {
                Text(
                    "${Money.format(voucher.foreignAmount)} ${voucher.currency}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}
