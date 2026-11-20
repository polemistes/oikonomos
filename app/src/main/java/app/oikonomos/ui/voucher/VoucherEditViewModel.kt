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
import app.oikonomos.data.CompanyResult
import app.oikonomos.domain.ExchangeRates
import app.oikonomos.domain.Money
import app.oikonomos.domain.OrgNumber
import app.oikonomos.domain.ReceiptParser
import app.oikonomos.domain.ReceiptSuggestion
import app.oikonomos.domain.VoucherPermissions
import app.oikonomos.domain.VoucherRules
import app.oikonomos.ocr.ReceiptLanguage
import app.oikonomos.ui.UiMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    val orgNumber: String = "",
    /** Navnet organisasjonsnummeret er registrert med i Enhetsregisteret, når oppslaget har funnet det. */
    val registeredName: String? = null,
    /** Sant når Enhetsregisteret har svart at nummeret ikke finnes. */
    val orgNumberUnknown: Boolean = false,
    /** ISO-kode når kvitteringen er i fremmed valuta, tom for norske kroner. */
    val currency: String = "",
    /** Beløpet slik det står på kvitteringen, i [currency]. [amount] er da det samme i kroner. */
    val foreignAmount: String = "",
    val foreignAmountError: Boolean = false,
    /** Kilden og datoen for kursen bak kronebeløpet. Tom når brukeren har skrevet kronebeløpet selv. */
    val rateSource: String = "",
    /** Kursen som nettopp er hentet, som «1 EUR = 10,5695 kr». Null når den ikke er hentet i dette skjemaet. */
    val fetchedRate: String? = null,
    val rateLoading: Boolean = false,
    /** Sant når siste forsøk på å hente kursen ikke ga noe. */
    val rateFailed: Boolean = false,
    /** Sant når oppslag på nett er slått av i innstillingene. */
    val lookupsOff: Boolean = false,
    /** Valutaene brukeren sist har brukt, til toppen av valutalisten. */
    val recentCurrencies: List<String> = emptyList(),
    /** Språket vedleggene leses som. */
    val language: ReceiptLanguage = ReceiptLanguage.DEFAULT,
)

/** Verdiene forslagene sist fylte inn. Et felt som fortsatt har samme verdi, har brukeren ikke rørt, og det kan erstattes. */
private data class Suggested(
    val amount: String = "",
    val foreignAmount: String = "",
    val currency: String = "",
    val counterparty: String = "",
    val orgNumber: String = "",
)

sealed interface EditEvent {
    data object Closed : EditEvent
    data class Message(val message: UiMessage) : EditEvent
}

class VoucherEditViewModel(private val voucherId: Long, start: String, private val container: AppContainer) : ViewModel() {
    private val repository = container.repository
    private val store = container.attachments
    private val online = container.online
    val languageModels = container.languageModels

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
    private var suggested = Suggested()
    private var rateJob: Job? = null
    private var companyJob: Job? = null

    val isNew get() = voucherId == 0L

    init {
        viewModelScope.launch {
            val settings = container.settings.settings.first()
            // Et språk som mangler modellen sin, for eksempel etter gjenoppretting på en ny telefon, leses som standard.
            val language = ReceiptLanguage.byId(settings.receiptLanguage).takeIf(languageModels::isReady) ?: ReceiptLanguage.DEFAULT
            val recent = repository.recentCurrencies()
            if (isNew) {
                state = state.copy(loading = false, language = language, recentCurrencies = recent, lookupsOff = !settings.onlineLookups)
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
                permissions = VoucherRules.permissions(voucher, repository.lastNumber(voucher.year)),
                pending = voucher.isPending,
                fromInvoice = voucher.invoiceId != null,
                cancelledAt = voucher.cancelledAt,
                cancelReason = voucher.cancelReason,
                tripId = voucher.tripId,
                projectId = voucher.projectId,
                orgNumber = voucher.counterpartyOrgNumber,
                currency = voucher.currency,
                foreignAmount = if (voucher.foreignAmount == 0L) "" else Money.formatPlain(voucher.foreignAmount),
                rateSource = voucher.exchangeRateSource,
                lookupsOff = !settings.onlineLookups,
                recentCurrencies = recent,
                language = language,
            )
            if (state.permissions.editAll) {
                lookUpCompany()
                // En kvittering som ble lagt til side uten nett, får kronebeløpet sitt når den åpnes igjen.
                if (voucher.currency.isNotEmpty() && voucher.amountOre == 0L) refreshRate(force = false, wait = false)
                files.firstOrNull()?.let { loadVendorCandidates(it) }
            }
        }
    }

    fun setType(type: VoucherType) {
        // Kategoriene hører til enten utgifter eller inntekter, så valget nullstilles når typen byttes.
        if (type != state.type) state = state.copy(type = type, categoryId = null, dirty = true)
    }

    /** Kronebeløpet. Det brukeren skriver selv, for eksempel det banken trakk, gjelder foran en hentet kurs. */
    fun setAmount(text: String) {
        rateJob?.cancel()
        state = state.copy(
            amount = text, amountError = false, rateSource = "", fetchedRate = null, rateLoading = false, rateFailed = false, dirty = true,
        )
    }

    fun setForeignAmount(text: String) {
        state = state.copy(foreignAmount = text, foreignAmountError = false, dirty = true)
        refreshRate(force = false)
    }

    /** Bytter valuta. [code] er tom for norske kroner. Beløpet som alt er skrevet, følger med til riktig felt. */
    fun setCurrency(code: String) {
        val before = state
        if (code == before.currency) return
        rateJob?.cancel()
        val cleared = before.copy(
            currency = code, rateSource = "", fetchedRate = null, rateLoading = false, rateFailed = false,
            amountError = false, foreignAmountError = false, dirty = true,
        )
        // Et kronebeløp som er regnet ut fra en kurs, gjelder ikke lenger når valutaen byttes.
        val computed = before.rateSource.isNotEmpty() || before.amount.isBlank()
        state = when {
            code.isEmpty() -> cleared.copy(amount = if (computed) before.foreignAmount else before.amount, foreignAmount = "")
            // Utenlandsk MVA kan ikke trekkes fra i norsk MVA-melding, så hele beløpet er kostnaden.
            before.currency.isEmpty() -> cleared.copy(foreignAmount = before.amount, amount = "", vatRate = 0)
            else -> cleared.copy(amount = if (computed) "" else before.amount)
        }
        refreshRate(force = false, wait = false)
    }

    /** Henter kursen på nytt og erstatter kronebeløpet, også når brukeren har skrevet det selv. */
    fun fetchRate() = refreshRate(force = true, wait = false)

    fun setDate(date: LocalDate) {
        dateChosen = true
        state = state.copy(date = date, dirty = true)
        refreshRate(force = false, wait = false)
    }

    /**
     * Regner beløpet om til kroner etter kursen på bilagets dato. Uten [force] røres ikke et
     * kronebeløp brukeren har skrevet selv. Med [wait] ventes det til brukeren har skrevet ferdig.
     */
    private fun refreshRate(force: Boolean, wait: Boolean = true) {
        rateJob?.cancel()
        val current = state
        val foreign = Money.parse(current.foreignAmount)?.takeIf { it > 0 }
        val computed = current.amount.isBlank() || current.rateSource.isNotEmpty()
        if (current.currency.isEmpty() || foreign == null || !(force || computed)) {
            state = current.copy(rateLoading = false)
            return
        }
        rateJob = viewModelScope.launch {
            if (wait) delay(RATE_DELAY_MS)
            state = state.copy(rateLoading = true, rateFailed = false)
            val rate = online.rate(current.currency, current.date)
            state = if (rate == null) {
                state.copy(rateLoading = false, rateFailed = true, lookupsOff = !online.enabled())
            } else {
                state.copy(
                    amount = Money.formatPlain(ExchangeRates.toOre(foreign, rate.nokPerUnit)),
                    amountError = false,
                    rateSource = ExchangeRates.sourceLabel(rate),
                    fetchedRate = ExchangeRates.describe(rate.currency, rate.nokPerUnit),
                    rateLoading = false,
                    rateFailed = false,
                )
            }
        }
    }

    fun setOrgNumber(text: String) {
        state = state.copy(orgNumber = text.filter(Char::isDigit).take(9), registeredName = null, orgNumberUnknown = false, dirty = true)
        lookUpCompany()
    }

    /**
     * Slår opp organisasjonsnummeret i Enhetsregisteret. Navnet derfra er sikrere enn det som er lest
     * av kvitteringen, og erstatter derfor et forslag, men aldri noe brukeren har skrevet selv.
     */
    private fun lookUpCompany() {
        companyJob?.cancel()
        val number = state.orgNumber
        if (!OrgNumber.isValid(number)) return
        companyJob = viewModelScope.launch {
            when (val result = online.company(number)) {
                is CompanyResult.Found -> {
                    val name = result.company.name
                    val untouched = state.counterparty.isBlank() || state.counterparty == suggested.counterparty
                    state = state.copy(registeredName = name, counterparty = if (untouched) name else state.counterparty)
                    if (untouched) suggested = suggested.copy(counterparty = name)
                }
                CompanyResult.NotFound -> state = state.copy(orgNumberUnknown = true)
                CompanyResult.Unavailable -> Unit
            }
        }
    }

    /** Bytter språket vedleggene leses som, og leser det første vedlegget på nytt. */
    fun setLanguage(language: ReceiptLanguage) {
        if (language == state.language) return
        state = state.copy(language = language)
        viewModelScope.launch {
            container.settings.update { it.copy(receiptLanguage = language.id) }
            val first = state.attachments.firstOrNull()
            if (first != null && state.permissions.editAll) {
                state = state.copy(reading = true)
                suggestFrom(first)
                state = state.copy(reading = false)
            }
        }
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

    /**
     * Fyller ut feltene med det som kan leses av vedlegget. Det brukeren selv har skrevet, blir stående.
     * Felt som er tomme, eller som bare inneholder et tidligere forslag, får det nye forslaget.
     */
    private suspend fun suggestFrom(file: StoredFile) {
        val suggestion = read(file) ?: return
        val before = state
        val previous = suggested
        fun untouched(current: String, old: String) = current.isBlank() || current == old

        var next = before.copy(vendorCandidates = suggestion.vendorCandidates)
        var written = previous
        // Beløp og valuta hører sammen: er ett av dem rørt, blir begge stående.
        val amountUntouched = untouched(before.amount, previous.amount) || before.rateSource.isNotEmpty()
        if (before.currency == previous.currency && amountUntouched && untouched(before.foreignAmount, previous.foreignAmount)) {
            val currency = suggestion.currency.orEmpty()
            val amount = suggestion.amountOre?.let(Money::formatPlain).orEmpty()
            next = if (currency.isEmpty()) {
                next.copy(currency = "", amount = amount, foreignAmount = "", rateSource = "", fetchedRate = null, rateFailed = false)
            } else {
                next.copy(currency = currency, amount = "", foreignAmount = amount, rateSource = "", fetchedRate = null, vatRate = 0)
            }
            written = written.copy(amount = next.amount, foreignAmount = next.foreignAmount, currency = currency)
        }
        if (untouched(before.counterparty, previous.counterparty)) {
            next = next.copy(counterparty = suggestion.vendor.orEmpty())
            written = written.copy(counterparty = next.counterparty)
        }
        if (untouched(before.orgNumber, previous.orgNumber)) {
            next = next.copy(orgNumber = suggestion.orgNumber.orEmpty(), registeredName = null, orgNumberUnknown = false)
            written = written.copy(orgNumber = next.orgNumber)
        }
        if (!dateChosen && suggestion.date != null) next = next.copy(date = suggestion.date)
        state = next
        suggested = written
        if (next.copy(vendorCandidates = before.vendorCandidates) != before) {
            eventChannel.send(EditEvent.Message(UiMessage(R.string.suggestion_applied)))
        }
        lookUpCompany()
        refreshRate(force = false, wait = false)
    }

    /** Henter bare leverandørforslagene, for et bilag som åpnes igjen og fortsatt kan rettes. Feltene røres ikke. */
    private suspend fun loadVendorCandidates(file: StoredFile) {
        val suggestion = read(file) ?: return
        state = state.copy(vendorCandidates = suggestion.vendorCandidates)
    }

    private suspend fun read(file: StoredFile): ReceiptSuggestion? {
        val ownOrgNumber = container.settings.settings.first().orgNumber
        return container.textReader.read(store.file(file.fileName), file.mimeType)
            ?.let { ReceiptParser.parse(it, LocalDate.now(), ownOrgNumber) }
    }

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
        // Beløpet i valutaen er det som står på kvitteringen, og må være med når bilaget føres.
        val foreignAmount = when {
            current.currency.isEmpty() -> 0L
            current.foreignAmount.isBlank() && !booking -> 0L
            else -> Money.parse(current.foreignAmount)?.takeIf { it > 0 }
        }
        if (foreignAmount == null) {
            state = current.copy(foreignAmountError = true)
            return
        }
        rateJob?.cancel()
        viewModelScope.launch {
            state = state.copy(saving = true, rateLoading = false)
            val vatRegistered = container.settings.settings.first().vatRegistered
            val vatRate = if (vatRegistered && current.currency.isEmpty()) current.vatRate else 0
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
                counterpartyOrgNumber = current.orgNumber,
                currency = current.currency,
                foreignAmount = foreignAmount,
                exchangeRateSource = current.rateSource,
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

    /** Angrer føringen av årets siste bilag. Endringer som ikke er lagret, forkastes. */
    fun unbook() {
        viewModelScope.launch {
            if (repository.unbook(voucherId)) {
                deleteNewFiles()
                finished = true
                eventChannel.send(EditEvent.Closed)
            } else {
                eventChannel.send(EditEvent.Message(UiMessage(R.string.unbook_not_last)))
            }
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

    private companion object {
        /** Hvor lenge det ventes etter siste tastetrykk før kursen hentes. */
        const val RATE_DELAY_MS = 700L
    }
}
