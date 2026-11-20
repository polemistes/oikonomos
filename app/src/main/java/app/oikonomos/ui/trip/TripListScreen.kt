package app.oikonomos.ui.trip

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.oikonomos.R
import app.oikonomos.data.Project
import app.oikonomos.data.Trip
import app.oikonomos.data.Voucher
import app.oikonomos.data.VoucherRepository
import app.oikonomos.data.VoucherType
import app.oikonomos.domain.Money
import app.oikonomos.ui.DateField
import app.oikonomos.ui.DropdownField
import app.oikonomos.ui.FormDialog
import app.oikonomos.ui.formatted
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Tallene som vises for en reise: bilag i regnskapet, kostnadene deres uten MVA, og kvitteringer som ennå er uavklart. */
data class TripTotals(val booked: Int, val costOre: Long, val pending: Int)

fun tripTotals(vouchers: List<Voucher>): TripTotals = TripTotals(
    booked = vouchers.count { it.counts },
    costOre = vouchers.filter { it.counts && it.type == VoucherType.EXPENSE }.sumOf { it.netOre },
    pending = vouchers.count { it.isPending },
)

data class TripRow(val trip: Trip, val totals: TripTotals)

class TripListViewModel(private val repository: VoucherRepository) : ViewModel() {
    /** Null til listen er lastet. */
    val rows: StateFlow<List<TripRow>?> = combine(repository.observeTrips(), repository.observeVouchersOnTrips()) { trips, vouchers ->
        val byTrip = vouchers.groupBy { it.tripId }
        trips.map { TripRow(it, tripTotals(byTrip[it.id].orEmpty())) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun create(trip: Trip, onCreated: (Long) -> Unit) {
        viewModelScope.launch { onCreated(repository.saveTrip(trip)) }
    }

    val projects: StateFlow<List<Project>> = repository.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun TripListScreen(
    viewModel: TripListViewModel,
    bottomBar: @Composable () -> Unit,
    onOpen: (Long) -> Unit,
    onSettings: () -> Unit,
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val projects by viewModel.projects.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_trips)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.trip_new)) },
            )
        },
    ) { padding ->
        val trips = rows ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(trips, key = { it.trip.id }) { row ->
                ListItem(
                    modifier = Modifier.clickable { onOpen(row.trip.id) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(row.trip.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            Text("${row.trip.fromDate.formatted()} – ${row.trip.toDate.formatted()}")
                            if (row.totals.pending > 0) {
                                Text(
                                    stringResource(R.string.trip_pending, row.totals.pending),
                                    color = MaterialTheme.colorScheme.tertiary,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Money.format(row.totals.costOre), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.overview_count, row.totals.booked), style = MaterialTheme.typography.labelMedium)
                        }
                    },
                )
                HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (trips.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.trips_empty_title), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.trips_empty_body),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        val today = LocalDate.now()
        TripDialog(
            trip = Trip(name = "", fromDate = today, toDate = today),
            projects = projects,
            onDismiss = { creating = false },
            onSave = {
                creating = false
                // En ny reise åpnes med en gang, så kvitteringene fra perioden kan gås gjennom.
                viewModel.create(it, onOpen)
            },
        )
    }
}

@Composable
fun TripDialog(trip: Trip, projects: List<Project>, onDismiss: () -> Unit, onSave: (Trip) -> Unit) {
    var draft by remember { mutableStateOf(trip) }
    FormDialog(
        title = stringResource(if (trip.id == 0L) R.string.trip_new else R.string.trip_edit),
        onDismiss = onDismiss,
        onSave = { onSave(draft.copy(name = draft.name.trim(), note = draft.note.trim())) },
        saveEnabled = draft.name.isNotBlank(),
    ) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it) },
            label = { Text(stringResource(R.string.trip_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        // Sluttdatoen følger med når startdatoen settes senere, så perioden aldri går baklengs.
        DateField(
            stringResource(R.string.trip_from),
            draft.fromDate,
            { date -> draft = draft.copy(fromDate = date, toDate = maxOf(date, draft.toDate)) },
            Modifier.fillMaxWidth(),
        )
        DateField(
            stringResource(R.string.trip_to),
            draft.toDate,
            { date -> draft = draft.copy(toDate = date, fromDate = minOf(date, draft.fromDate)) },
            Modifier.fillMaxWidth(),
        )
        val projectOptions = projects.filter { it.active || it.id == draft.projectId }
        if (projectOptions.isNotEmpty()) {
            val none = stringResource(R.string.no_project)
            DropdownField(
                label = stringResource(R.string.field_project),
                selectedText = projects.firstOrNull { it.id == draft.projectId }?.name ?: none,
                options = listOf<Pair<Long?, String>>(null to none) + projectOptions.map { it.id to it.name },
                onSelect = { draft = draft.copy(projectId = it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = draft.note,
            onValueChange = { draft = draft.copy(note = it) },
            label = { Text(stringResource(R.string.trip_note)) },
            supportingText = { Text(stringResource(R.string.trip_note_hint)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
