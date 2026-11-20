package app.oikonomos.ui.voucher

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.oikonomos.AppContainer
import app.oikonomos.R
import app.oikonomos.data.Category
import app.oikonomos.data.Project
import app.oikonomos.data.StoredFile
import app.oikonomos.data.Trip
import app.oikonomos.data.VoucherDraft
import app.oikonomos.data.VoucherType
import app.oikonomos.data.YearChangeException
import app.oikonomos.domain.Money
import app.oikonomos.domain.ReceiptParser
import app.oikonomos.domain.ReceiptSuggestion
import app.oikonomos.domain.VoucherPermissions
import app.oikonomos.domain.VoucherRules
import app.oikonomos.ui.UiMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Hva bilagsskjemaet skal gjøre idet det åpnes. */
const val START_MANUAL = ""
const val START_SCAN = "scan"
const val START_PICK = "pick"
const val START_SHARED = "shared"

data class EditUiState(
    val loading: Boolean = true,
    /** Bilagsnummeret, tomt for et bilag som ikke er lagret ennå. */
    val label: String = "",
    val type: VoucherType = VoucherType.EXPENSE,
    val date: LocalDate = LocalDate.now(),
    val amount: String = "",
    val amountError: Boolean = false,
    val vatRate: Int = 0,
    val counterparty: String = "",
    val description: String = "",
    val categoryId: Long? = null,
    val attachments: List<StoredFile> = emptyList(),
    /** Sant mens filer importeres og leses. */
    val reading: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val permissions: VoucherPermissions = VoucherPermissions.NEW,
    /** Sant for en kvittering som ikke er ført i regnskapet ennå, også en som ikke er lagret. */
    val pending: Boolean = true,
    /** Sant for bilag laget fra en faktura. Da kan bare kategori og beskrivelse endres. */
    val fromInvoice: Boolean = false,
    /** Tidspunktet bilaget ble annullert, null for bilag som gjelder. */
    val cancelledAt: Long? = null,
    val cancelReason: String = "",
    val tripId: Long? = null,
    val projectId: Long? = null,
    /** Linjer fra toppen av kvitteringen som brukeren kan velge som leverandør. */
    val vendorCandidates: List<String> = emptyList(),
)

sealed interface EditEvent {
    data object Closed : EditEvent
    data class Message(val message: UiMessage) : EditEvent
}

class VoucherEditViewModel(private val voucherId: Long, start: String, private val container: AppContainer) : ViewModel() {
    private val repository = container.repository
    private val store = container.attachments

    var state by mutableStateOf(EditUiState())
        private set

    val categories: StateFlow<List<Category>> = container.database.categoryDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val trips: StateFlow<List<Trip>> = repository.observeTrips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val projects: StateFlow<List<Project>> = repository.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val vatRegistered: StateFlow<Boolean> = container.settings.settings.map { it.vatRegistered }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val eventChannel = Channel<EditEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    /** Filene bilaget hadde da det ble åpnet. Andre filer i skjemaet er nye og slettes hvis skjemaet forkastes. */
    private var originalFiles = emptySet<String>()
    private var finished = false
    private var dateChosen = false

    val isNew get() = voucherId == 0L

    init {
        viewModelScope.launch {
            if (isNew) {
                state = state.copy(loading = false)
                if (start == START_SHARED) addFiles(container.sharedInbox.getAndUpdate { emptyList() })
                return@launch
            }
            val existing = repository.get(voucherId)
            if (existing == null) {
                eventChannel.send(EditEvent.Closed)
                return@launch
            }
            val voucher = existing.voucher
            val files = existing.sortedAttachments.map { StoredFile(it.fileName, it.mimeType) }
            originalFiles = files.map { it.fileName }.toSet()
            dateChosen = true
            state = EditUiState(
                loading = false,
                label = voucher.label,
                type = voucher.type,
                date = voucher.date,
                // En uavklart kvittering kan være lagret uten beløp.
                amount = if (voucher.amountOre == 0L) "" else Money.formatPlain(voucher.amountOre),
                vatRate = voucher.vatRate,
                counterparty = voucher.counterparty,
                description = voucher.description,
                categoryId = voucher.categoryId,
                attachments = files,
                permissions = VoucherRules.permissions(voucher),
                pending = voucher.isPending,
                fromInvoice = voucher.invoiceId != null,
                cancelledAt = voucher.cancelledAt,
                cancelReason = voucher.cancelReason,
                tripId = voucher.tripId,
                projectId = voucher.projectId,
            )
            if (state.permissions.editAll) files.firstOrNull()?.let { loadVendorCandidates(it) }
        }
    }

    fun setType(type: VoucherType) {
        // Kategoriene hører til enten utgifter eller inntekter, så valget nullstilles når typen byttes.
        if (type != state.type) state = state.copy(type = type, categoryId = null, dirty = true)
    }

    fun setAmount(text: String) {
        state = state.copy(amount = text, amountError = false, dirty = true)
    }

    fun setDate(date: LocalDate) {
        dateChosen = true
        state = state.copy(date = date, dirty = true)
    }

    fun setVatRate(rate: Int) {
        state = state.copy(vatRate = rate, dirty = true)
    }

    fun setCounterparty(text: String) {
        state = state.copy(counterparty = text, dirty = true)
    }

    fun setDescription(text: String) {
        state = state.copy(description = text, dirty = true)
    }

    /** Et bilag uten prosjekt får reisens prosjekt når det knyttes til en reise. */
    fun setTrip(id: Long?) {
        val inherited = state.projectId ?: trips.value.firstOrNull { it.id == id }?.projectId
        state = state.copy(tripId = id, projectId = inherited, dirty = true)
    }

    fun setProject(id: Long?) {
        state = state.copy(projectId = id, dirty = true)
    }

    fun setCategory(id: Long?) {
        state = state.copy(categoryId = id, dirty = true)
    }

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            state = state.copy(reading = true)
            val added = uris.mapNotNull { store.import(it) }
            if (added.size < uris.size) eventChannel.send(EditEvent.Message(UiMessage(R.string.import_failed)))
            val wasEmpty = state.attachments.isEmpty()
            if (added.isNotEmpty()) state = state.copy(attachments = state.attachments + added, dirty = true)
            if (wasEmpty && added.isNotEmpty()) suggestFrom(added.first())
            state = state.copy(reading = false)
        }
    }

    fun removeAttachment(file: StoredFile) {
        state = state.copy(attachments = state.attachments - file, dirty = true)
        if (file.fileName !in originalFiles) store.delete(file.fileName)
    }

    /** Fyller ut tomme felt med det som kan leses av vedlegget. Det brukeren selv har skrevet, blir stående. */
    private suspend fun suggestFrom(file: StoredFile) {
        val suggestion = read(file) ?: return
        val before = state
        state = before.copy(
            amount = before.amount.ifBlank { suggestion.amountOre?.let(Money::formatPlain).orEmpty() },
            counterparty = before.counterparty.ifBlank { suggestion.vendor.orEmpty() },
            date = suggestion.date?.takeUnless { dateChosen } ?: before.date,
            vendorCandidates = suggestion.vendorCandidates,
        )
        if (state.copy(vendorCandidates = before.vendorCandidates) != before) {
            eventChannel.send(EditEvent.Message(UiMessage(R.string.suggestion_applied)))
        }
    }

    /** Henter bare leverandørforslagene, for et bilag som åpnes igjen og fortsatt kan rettes. Feltene røres ikke. */
    private suspend fun loadVendorCandidates(file: StoredFile) {
        val suggestion = read(file) ?: return
        state = state.copy(vendorCandidates = suggestion.vendorCandidates)
    }

    private suspend fun read(file: StoredFile): ReceiptSuggestion? =
        container.textReader.read(store.file(file.fileName), file.mimeType)?.let { ReceiptParser.parse(it, LocalDate.now()) }

    /**
     * Lagrer kvitteringen. Med [book] føres den i regnskapet og får bilagsnummer. Uten blir den
     * stående som uavklart. Et bilag som alt er ført, forblir ført.
     */
    fun save(book: Boolean) {
        val current = state
        if (current.saving || current.reading) return
        if (!current.permissions.editAll) {
            viewModelScope.launch {
                state = state.copy(saving = true)
                repository.updateClassification(voucherId, current.categoryId, current.description, current.projectId)
                finished = true
                eventChannel.send(EditEvent.Closed)
            }
            return
        }
        val booking = book || !current.pending
        val parsed = Money.parse(current.amount)?.takeIf { it > 0 }
        // Beløpet kan vente til kvitteringen føres i regnskapet.
        val amountOre = parsed ?: if (current.amount.isBlank() && !booking) 0L else null
        if (amountOre == null) {
            state = current.copy(amountError = true)
            return
        }
        viewModelScope.launch {
            state = state.copy(saving = true)
            val vatRate = if (container.settings.settings.first().vatRegistered) current.vatRate else 0
            val draft = VoucherDraft(
                id = voucherId,
                type = current.type,
                date = current.date,
                amountOre = amountOre,
                vatRate = vatRate,
                vatOre = Money.vatPart(amountOre, vatRate),
                counterparty = current.counterparty,
                description = current.description,
                categoryId = current.categoryId,
                attachments = current.attachments,
                tripId = current.tripId,
                projectId = current.projectId,
            )
            try {
                repository.save(draft, book = booking)
            } catch (_: YearChangeException) {
                state = state.copy(saving = false)
                eventChannel.send(EditEvent.Message(UiMessage(R.string.year_change_blocked)))
                return@launch
            }
            finished = true
            eventChannel.send(EditEvent.Closed)
        }
    }

    /** Sletter en uavklart kvittering. */
    fun delete() {
        viewModelScope.launch {
            repository.delete(voucherId)
            deleteNewFiles()
            finished = true
            eventChannel.send(EditEvent.Closed)
        }
    }

    /** Annullerer et bilag i regnskapet. Endringer som ikke er lagret, forkastes. */
    fun cancel(reason: String) {
        viewModelScope.launch {
            repository.cancel(voucherId, reason)
            deleteNewFiles()
            finished = true
            eventChannel.send(EditEvent.Closed)
        }
    }

    override fun onCleared() {
        if (!finished) deleteNewFiles()
    }

    private fun deleteNewFiles() {
        state.attachments.filter { it.fileName !in originalFiles }.forEach { store.delete(it.fileName) }
    }
}
