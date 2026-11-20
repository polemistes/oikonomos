package app.oikonomos.data

import androidx.room.withTransaction
import app.oikonomos.domain.InvoiceCalc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Hvorfor en faktura ikke kunne utstedes. */
enum class IssueProblem { SELLER_INCOMPLETE, NO_LINES }

class IssueException(val problem: IssueProblem) : Exception()

class InvoiceRepository(
    private val db: AppDatabase,
    private val store: AttachmentStore,
    private val settings: SettingsStore,
) {
    private val invoices = db.invoiceDao()
    private val customers = db.customerDao()
    private val vouchers = db.voucherDao()

    fun observeInvoices(): Flow<List<InvoiceListItem>> = invoices.observeAll()

    fun observeCustomers(): Flow<List<Customer>> = customers.observeAll()

    suspend fun get(id: Long): InvoiceWithLines? = invoices.get(id)

    suspend fun isCredited(id: Long): Boolean = invoices.isCredited(id)

    /** Fakturaen som en kreditnota reverserer. */
    suspend fun creditedInvoice(creditNote: Invoice): Invoice? =
        creditNote.creditedInvoiceId?.let { invoices.get(it)?.invoice }

    /** PDF-en ligger som vedlegg på inntektsbilaget som ble laget da fakturaen ble utstedt. */
    suspend fun pdfFile(invoiceId: Long): File? =
        vouchers.forInvoice(invoiceId)?.sortedAttachments?.firstOrNull()?.let { store.file(it.fileName) }

    suspend fun saveCustomer(customer: Customer): Long =
        if (customer.id == 0L) {
            customers.insert(customer)
        } else {
            customers.update(customer)
            customer.id
        }

    /** Gir antall fakturaer som bruker kunden. Kunden slettes bare når tallet er 0. */
    suspend fun deleteCustomer(customer: Customer): Int = db.withTransaction {
        val used = customers.usageCount(customer.id)
        if (used == 0) customers.delete(customer)
        used
    }

    /** Lagrer en kladd. Utstedte fakturaer kan ikke endres og avvises her. */
    suspend fun saveDraft(invoice: Invoice, lines: List<InvoiceLine>): Long {
        val vatRegistered = settings.settings.first().vatRegistered
        return db.withTransaction {
            val existing = if (invoice.id != 0L) invoices.get(invoice.id)?.invoice else null
            check(existing == null || existing.isDraft) { "Utstedte fakturaer kan ikke endres" }
            val totals = InvoiceCalc.totals(lines, vatRegistered)
            val draft = invoice.copy(number = null, totalOre = totals.grossOre, vatOre = totals.vatOre)
            val id = if (existing == null) {
                invoices.insert(draft.copy(id = 0))
            } else {
                invoices.update(draft)
                draft.id
            }
            invoices.deleteLines(id)
            invoices.insertLines(lines.mapIndexed { index, line -> line.copy(id = 0, invoiceId = id, position = index) })
            id
        }
    }

    suspend fun deleteDraft(id: Long) = invoices.deleteDraft(id)

    /**
     * Utsteder en kladd: tildeler neste fakturanummer, lager PDF-en og fører inntekten som et bilag
     * med PDF-en som vedlegg. Alt skjer i én transaksjon, så et nummer aldri brukes uten at fakturaen finnes.
     */
    suspend fun issue(id: Long): Int = withContext(Dispatchers.IO) {
        val seller = settings.settings.first()
        if (seller.companyName.isBlank() || seller.orgNumber.isBlank()) throw IssueException(IssueProblem.SELLER_INCOMPLETE)
        val pdf = store.file("${UUID.randomUUID()}.pdf")
        try {
            db.withTransaction {
                val draft = checkNotNull(invoices.get(id)) { "Fakturaen finnes ikke" }
                check(draft.invoice.isDraft) { "Fakturaen er allerede utstedt" }
                val lines = draft.sortedLines
                if (lines.isEmpty()) throw IssueException(IssueProblem.NO_LINES)

                val totals = InvoiceCalc.totals(lines, seller.vatRegistered)
                val number = InvoiceCalc.nextNumber(invoices.maxNumber(), seller.invoiceNumberStart)
                val issued = draft.invoice.copy(number = number, totalOre = totals.grossOre, vatOre = totals.vatOre)
                val credited = creditedInvoice(issued)
                InvoicePdf.write(InvoiceDocument(seller, draft.customer, issued, lines, totals, credited), pdf)

                invoices.update(issued)
                val year = issued.issueDate.year
                val voucherId = vouchers.insert(
                    Voucher(
                        year = year,
                        number = vouchers.nextNumber(year),
                        type = VoucherType.INCOME,
                        date = issued.issueDate,
                        amountOre = totals.grossOre,
                        vatRate = totals.uniformVatRate,
                        vatOre = totals.vatOre,
                        counterparty = draft.customer.name,
                        description = (if (issued.isCreditNote) "Kreditnota " else "Faktura ") + number,
                        categoryId = defaultIncomeCategory(),
                        createdAt = System.currentTimeMillis(),
                        invoiceId = id,
                    ),
                )
                vouchers.insertAttachments(
                    listOf(Attachment(voucherId = voucherId, fileName = pdf.name, mimeType = "application/pdf", position = 0)),
                )
                number
            }
        } catch (e: Throwable) {
            pdf.delete()
            throw e
        }
    }

    /** Lager og utsteder en kreditnota som reverserer hele fakturaen. Gir kreditnotaens id. */
    suspend fun credit(invoiceId: Long, date: LocalDate): Long {
        val original = checkNotNull(invoices.get(invoiceId)) { "Fakturaen finnes ikke" }
        check(!original.invoice.isDraft && !original.invoice.isCreditNote && !invoices.isCredited(invoiceId)) {
            "Fakturaen kan ikke krediteres"
        }
        val creditNoteId = saveDraft(
            Invoice(
                customerId = original.invoice.customerId,
                issueDate = date,
                dueDate = date,
                deliveryDate = original.invoice.deliveryDate,
                deliveryPlace = original.invoice.deliveryPlace,
                customerReference = original.invoice.customerReference,
                creditedInvoiceId = invoiceId,
                createdAt = System.currentTimeMillis(),
            ),
            InvoiceCalc.negated(original.sortedLines),
        )
        try {
            issue(creditNoteId)
        } catch (e: Throwable) {
            invoices.deleteDraft(creditNoteId)
            throw e
        }
        return creditNoteId
    }

    /** [date] er null for å angre at fakturaen er merket som betalt. */
    suspend fun setPaid(id: Long, date: LocalDate?) {
        db.withTransaction {
            val invoice = invoices.get(id)?.invoice ?: return@withTransaction
            if (!invoice.isDraft) invoices.update(invoice.copy(paidDate = date))
        }
    }

    /** Første inntektskategori som er i bruk, i kontorekkefølge. Brukeren kan bytte den på bilaget. */
    private suspend fun defaultIncomeCategory(): Long? =
        db.categoryDao().all()
            .filter { it.type == VoucherType.INCOME && it.active }
            .minWithOrNull(compareBy({ it.account }, { it.name }))?.id
}
