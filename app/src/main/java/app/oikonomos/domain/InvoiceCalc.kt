package app.oikonomos.domain

import app.oikonomos.data.Invoice
import app.oikonomos.data.InvoiceLine
import java.time.LocalDate

enum class InvoiceStatus { DRAFT, UNPAID, OVERDUE, PAID, CREDITED, CREDIT_NOTE }

/** MVA for én sats: grunnlaget er summen av linjene med satsen. */
data class VatGroup(val rate: Int, val baseOre: Long, val vatOre: Long)

data class InvoiceTotals(val netOre: Long, val vatGroups: List<VatGroup>) {
    val vatOre: Long get() = vatGroups.sumOf { it.vatOre }
    val grossOre: Long get() = netOre + vatOre

    /** Satsen når alle linjer har samme sats, ellers 0. */
    val uniformVatRate: Int get() = vatGroups.singleOrNull()?.rate ?: 0
}

object InvoiceCalc {
    /** Linjebeløp uten MVA. Avrundes bort fra null, så en kreditnota blir nøyaktig det motsatte av fakturaen. */
    fun lineNet(line: InvoiceLine): Long = roundedDivide(line.quantityMilli * line.unitPriceOre, 1000)

    /** Selgere som ikke er MVA-registrert, skal ikke beregne MVA, uansett hva som står på linjene. */
    fun totals(lines: List<InvoiceLine>, vatRegistered: Boolean): InvoiceTotals {
        val net = lines.sumOf(::lineNet)
        if (!vatRegistered) return InvoiceTotals(net, emptyList())
        val groups = lines.groupBy { it.vatRate }.toSortedMap().mapNotNull { (rate, group) ->
            val base = group.sumOf(::lineNet)
            if (rate <= 0) null else VatGroup(rate, base, roundedDivide(base * rate, 100))
        }
        return InvoiceTotals(net, groups)
    }

    /** Neste fakturanummer: ett høyere enn det siste, men aldri lavere enn startnummeret fra innstillingene. */
    fun nextNumber(highestIssued: Int?, start: Int): Int = maxOf((highestIssued ?: 0) + 1, start)

    fun status(invoice: Invoice, credited: Boolean, today: LocalDate): InvoiceStatus = when {
        invoice.isDraft -> InvoiceStatus.DRAFT
        invoice.isCreditNote -> InvoiceStatus.CREDIT_NOTE
        credited -> InvoiceStatus.CREDITED
        invoice.paidDate != null -> InvoiceStatus.PAID
        invoice.dueDate < today -> InvoiceStatus.OVERDUE
        else -> InvoiceStatus.UNPAID
    }

    /** Linjene med motsatt fortegn, til en kreditnota. */
    fun negated(lines: List<InvoiceLine>): List<InvoiceLine> =
        lines.map { it.copy(id = 0, invoiceId = 0, quantityMilli = -it.quantityMilli) }

    /** Tolker «2», «1,5» og «0.25» som antall i tusendeler. Gir null for alt annet, også null og negative tall. */
    fun parseQuantity(text: String): Long? {
        val match = Regex("^(\\d{1,7})(?:[.,](\\d{1,3}))?$").matchEntire(text.trim()) ?: return null
        val milli = match.groupValues[1].toLong() * 1000 + match.groupValues[2].padEnd(3, '0').toLong()
        return milli.takeIf { it > 0 }
    }

    /** «2», «1,5», «-0,25». */
    fun formatQuantity(milli: Long): String {
        val abs = Math.abs(milli)
        val fraction = (abs % 1000).toString().padStart(3, '0').trimEnd('0')
        val sign = if (milli < 0) "-" else ""
        return sign + (abs / 1000) + if (fraction.isEmpty()) "" else ",$fraction"
    }

    private fun roundedDivide(value: Long, divisor: Long): Long {
        val rounded = (Math.abs(value) + divisor / 2) / divisor
        return if (value < 0) -rounded else rounded
    }
}

object Formats {
    /** «123 456 789» for et organisasjonsnummer på ni sifre. Annet returneres som det er. */
    fun orgNumber(value: String): String {
        val digits = value.filter(Char::isDigit)
        return if (digits.length == 9) digits.chunked(3).joinToString(" ") else value.trim()
    }

    /** «1234.56.78903» for et kontonummer på elleve sifre. Annet returneres som det er. */
    fun bankAccount(value: String): String {
        val digits = value.filter(Char::isDigit)
        return if (digits.length == 11) "${digits.take(4)}.${digits.substring(4, 6)}.${digits.substring(6)}" else value.trim()
    }
}
