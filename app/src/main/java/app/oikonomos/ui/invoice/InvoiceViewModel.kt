package app.oikonomos.ui.invoice

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.oikonomos.AppContainer
import app.oikonomos.R
import app.oikonomos.data.AppSettings
import app.oikonomos.data.CompanyResult
import app.oikonomos.data.Customer
import app.oikonomos.domain.Company
import app.oikonomos.data.Invoice
import app.oikonomos.data.InvoiceLine
import app.oikonomos.data.IssueException
import app.oikonomos.data.IssueProblem
import app.oikonomos.domain.InvoiceCalc
import app.oikonomos.domain.InvoiceStatus
import app.oikonomos.ui.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class InvoiceUiState(
    val loading: Boolean = true,
    /** Fakturaen slik den er lagret, null før en ny kladd er lagret første gang. */
    val saved: Invoice? = null,
    val customerId: Long? = null,
    val issueDate: LocalDate = LocalDate.now(),
    val dueDate: LocalDate = LocalDate.now(),
    val deliveryDate: LocalDate = LocalDate.now(),
    val deliveryPlace: String = "",
    val customerReference: String = "",
    val note: String = "",
    val lines: List<InvoiceLine> = emptyList(),
    val dirty: Boolean = false,
    val busy: Boolean = false,
    /** For en utstedt faktura: kunden, statusen og eventuelt fakturaen en kreditnota gjelder. */
    val customer: Customer? = null,
    val status: InvoiceStatus = InvoiceStatus.DRAFT,
    val creditedInvoice: Invoice? = null,
) {
    val isIssued: Boolean get() = saved?.number != null
}

sealed interface InvoiceEvent {
    data object Closed : InvoiceEvent
    data object SellerIncomplete : InvoiceEvent
    data class Message(val message: UiMessage) : InvoiceEvent
}

class InvoiceViewModel(private var invoiceId: Long, private val container: AppContainer) : ViewModel() {
    private val repository = container.invoices

    var state by mutableStateOf(InvoiceUiState())
        private set

    val customers: StateFlow<List<Customer>> = repository.observeCustomers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val eventChannel = Channel<InvoiceEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    /** Forfallsdatoen følger fakturadatoen til brukeren selv har valgt en. */
    private var dueDateChosen = false

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        if (invoiceId == 0L) {
            val today = LocalDate.now()
            val paymentDays = container.settings.settings.first().paymentDays
            state = InvoiceUiState(loading = false, issueDate = today, dueDate = today.plusDays(paymentDays.toLong()), deliveryDate = today)
            return
        }
        val loaded = repository.get(invoiceId)
        if (loaded == null) {
            eventChannel.send(InvoiceEvent.Closed)
            return
        }
        val invoice = loaded.invoice
        dueDateChosen = true
        state = InvoiceUiState(
            loading = false,
            saved = invoice,
            customerId = invoice.customerId,
            issueDate = invoice.issueDate,
            dueDate = invoice.dueDate,
            deliveryDate = invoice.deliveryDate,
            deliveryPlace = invoice.deliveryPlace,
            customerReference = invoice.customerReference,
            note = invoice.note,
            lines = loaded.sortedLines,
            customer = loaded.customer,
            status = InvoiceCalc.status(invoice, repository.isCredited(invoice.id), LocalDate.now()),
            creditedInvoice = repository.creditedInvoice(invoice),
        )
    }

    fun setCustomer(customer: Customer) {
        // Kundens faste referanse foreslås, men overskriver ikke noe brukeren har skrevet.
        val reference = state.customerReference.ifBlank { customer.reference }
        state = state.copy(customerId = customer.id, customerReference = reference, dirty = true)
    }

    /** Lagrer en ny kunde fra fakturaskjemaet og velger den. */
    /** Foretaket et organisasjonsnummer er registrert på i Enhetsregisteret, eller null når det ikke ble funnet. */
    suspend fun lookUpCompany(orgNumber: String): Company? = (container.online.company(orgNumber) as? CompanyResult.Found)?.company

    fun addCustomer(customer: Customer) {
        viewModelScope.launch {
            val id = repository.saveCustomer(customer)
            setCustomer(customer.copy(id = id))
        }
    }

    fun setIssueDate(date: LocalDate) {
        val dueDate = if (dueDateChosen) state.dueDate else date.plusDays(settings.value.paymentDays.toLong())
        state = state.copy(issueDate = date, dueDate = dueDate, dirty = true)
    }

    fun setDueDate(date: LocalDate) {
        dueDateChosen = true
        state = state.copy(dueDate = date, dirty = true)
    }

    fun setDeliveryDate(date: LocalDate) {
        state = state.copy(deliveryDate = date, dirty = true)
    }

    fun setDeliveryPlace(text: String) {
        state = state.copy(deliveryPlace = text, dirty = true)
    }

    fun setCustomerReference(text: String) {
        state = state.copy(customerReference = text, dirty = true)
    }

    fun setNote(text: String) {
        state = state.copy(note = text, dirty = true)
    }

    /** [index] er null for en ny linje. */
    fun putLine(index: Int?, line: InvoiceLine) {
        val lines = state.lines.toMutableList()
        if (index == null) lines += line else lines[index] = line
        state = state.copy(lines = lines, dirty = true)
    }

    fun removeLine(index: Int) {
        state = state.copy(lines = state.lines.filterIndexed { i, _ -> i != index }, dirty = true)
    }

    fun saveDraft() {
        if (state.busy || state.isIssued) return
        viewModelScope.launch {
            if (persistDraft() != null) eventChannel.send(InvoiceEvent.Closed)
        }
    }

    fun issue() {
        if (state.busy || state.isIssued) return
        viewModelScope.launch {
            if (state.lines.isEmpty()) {
                eventChannel.send(InvoiceEvent.Message(UiMessage(R.string.issue_missing_lines)))
                return@launch
            }
            val id = persistDraft() ?: return@launch
            runBusy(R.string.issue_failed) { repository.issue(id) }
            // Lastes på nytt uansett utfall: enten er fakturaen utstedt, eller så er kladden lagret.
            load()
        }
    }

    fun deleteDraft() {
        viewModelScope.launch {
            if (invoiceId != 0L && !state.isIssued) repository.deleteDraft(invoiceId)
            eventChannel.send(InvoiceEvent.Closed)
        }
    }

    /** [date] er null for å angre. */
    fun setPaid(date: LocalDate?) {
        viewModelScope.launch {
            repository.setPaid(invoiceId, date)
            load()
        }
    }

    fun credit() {
        if (state.busy) return
        viewModelScope.launch {
            runBusy(R.string.credit_failed) { repository.credit(invoiceId, LocalDate.now()) }
            load()
        }
    }

    /** PDF-en i appens lager, for visning. Gir null når den mangler. */
    suspend fun pdfUri(): Uri? =
        repository.pdfFile(invoiceId)?.takeIf { it.exists() }?.let { container.attachments.uriFor(it.name) }

    /** En kopi med lesbart filnavn, for deling med kunden. */
    suspend fun shareablePdf(): Uri? {
        val invoice = state.saved ?: return null
        val file = repository.pdfFile(invoiceId)?.takeIf { it.exists() } ?: return null
        val name = (if (invoice.isCreditNote) "Kreditnota-" else "Faktura-") + invoice.number + ".pdf"
        return container.attachments.shareCopy(file, name)
    }

    /** Lagrer kladden og gir id-en, eller null når kunde mangler. */
    private suspend fun persistDraft(): Long? {
        val current = state
        val customerId = current.customerId
        if (customerId == null) {
            eventChannel.send(InvoiceEvent.Message(UiMessage(R.string.issue_missing_customer)))
            return null
        }
        val invoice = Invoice(
            id = invoiceId,
            customerId = customerId,
            issueDate = current.issueDate,
            dueDate = current.dueDate,
            deliveryDate = current.deliveryDate,
            deliveryPlace = current.deliveryPlace.trim(),
            customerReference = current.customerReference.trim(),
            note = current.note.trim(),
            createdAt = current.saved?.createdAt ?: System.currentTimeMillis(),
        )
        invoiceId = repository.saveDraft(invoice, current.lines)
        state = state.copy(dirty = false)
        return invoiceId
    }

    private suspend fun runBusy(failure: Int, task: suspend () -> Unit) {
        state = state.copy(busy = true)
        try {
            task()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IssueException) {
            eventChannel.send(
                when (e.problem) {
                    IssueProblem.SELLER_INCOMPLETE -> InvoiceEvent.SellerIncomplete
                    IssueProblem.NO_LINES -> InvoiceEvent.Message(UiMessage(R.string.issue_missing_lines))
                },
            )
        } catch (_: Exception) {
            eventChannel.send(InvoiceEvent.Message(UiMessage(failure)))
        }
        state = state.copy(busy = false)
    }
}
