package app.oikonomos.domain

import app.oikonomos.data.Invoice
import app.oikonomos.data.InvoiceLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class InvoiceCalcTest {
    private fun line(quantityMilli: Long, unitPriceOre: Long, vatRate: Int = 0) =
        InvoiceLine(description = "Linje", quantityMilli = quantityMilli, unitPriceOre = unitPriceOre, vatRate = vatRate)

    @Test
    fun lineAmountIsQuantityTimesPriceRoundedToWholeOre() {
        assertEquals(150000L, InvoiceCalc.lineNet(line(1500, 100000)))
        assertEquals(33333L, InvoiceCalc.lineNet(line(333, 100100)))
        // 0,125 × 1,00 kr er 12,5 øre, som rundes opp.
        assertEquals(13L, InvoiceCalc.lineNet(line(125, 100)))
    }

    @Test
    fun sellerWithoutVatRegistrationGetsNoVatWhateverTheLinesSay() {
        val totals = InvoiceCalc.totals(listOf(line(1000, 100000, vatRate = 25), line(2000, 5000)), vatRegistered = false)
        assertEquals(110000L, totals.netOre)
        assertEquals(0L, totals.vatOre)
        assertEquals(110000L, totals.grossOre)
        assertEquals(0, totals.uniformVatRate)
    }

    @Test
    fun vatIsCalculatedPerRateOnTheSumOfTheLines() {
        val totals = InvoiceCalc.totals(
            listOf(line(1000, 9999, vatRate = 25), line(1000, 9999, vatRate = 25), line(1000, 10000, vatRate = 15), line(1000, 5000)),
            vatRegistered = true,
        )
        assertEquals(34998L, totals.netOre)
        assertEquals(listOf(VatGroup(15, 10000, 1500), VatGroup(25, 19998, 5000)), totals.vatGroups)
        assertEquals(6500L, totals.vatOre)
        assertEquals(41498L, totals.grossOre)
        assertEquals(0, totals.uniformVatRate)
    }

    @Test
    fun uniformRateIsReportedWhenAllLinesShareIt() {
        val totals = InvoiceCalc.totals(listOf(line(1000, 10000, 25), line(2000, 10000, 25)), vatRegistered = true)
        assertEquals(25, totals.uniformVatRate)
        assertEquals(37500L, totals.grossOre)
    }

    @Test
    fun creditNoteIsTheExactNegativeOfTheInvoice() {
        val lines = listOf(line(125, 100, 25), line(333, 100100, 25), line(1500, 99999, 15))
        val original = InvoiceCalc.totals(lines, vatRegistered = true)
        val credit = InvoiceCalc.totals(InvoiceCalc.negated(lines), vatRegistered = true)
        assertEquals(-original.netOre, credit.netOre)
        assertEquals(-original.vatOre, credit.vatOre)
        assertEquals(-original.grossOre, credit.grossOre)
    }

    @Test
    fun nextNumberContinuesTheSeriesButNeverGoesBelowTheConfiguredStart() {
        assertEquals(1, InvoiceCalc.nextNumber(null, 1))
        assertEquals(40, InvoiceCalc.nextNumber(null, 40))
        assertEquals(41, InvoiceCalc.nextNumber(40, 40))
        assertEquals(41, InvoiceCalc.nextNumber(40, 1))
        assertEquals(100, InvoiceCalc.nextNumber(40, 100))
    }

    @Test
    fun parsesAndFormatsQuantities() {
        assertEquals(2000L, InvoiceCalc.parseQuantity("2"))
        assertEquals(1500L, InvoiceCalc.parseQuantity("1,5"))
        assertEquals(250L, InvoiceCalc.parseQuantity(" 0.25 "))
        assertNull(InvoiceCalc.parseQuantity("0"))
        assertNull(InvoiceCalc.parseQuantity("-1"))
        assertNull(InvoiceCalc.parseQuantity("1,2345"))
        assertNull(InvoiceCalc.parseQuantity("to"))
        assertEquals("2", InvoiceCalc.formatQuantity(2000))
        assertEquals("1,5", InvoiceCalc.formatQuantity(1500))
        assertEquals("-0,25", InvoiceCalc.formatQuantity(-250))
        assertEquals(1500L, InvoiceCalc.parseQuantity(InvoiceCalc.formatQuantity(1500)))
    }

    @Test
    fun statusFollowsNumberPaymentDueDateAndCrediting() {
        val today = LocalDate.of(2026, 11, 19)
        val draft = Invoice(customerId = 1, issueDate = today, dueDate = today.plusDays(14), deliveryDate = today, createdAt = 0)
        val issued = draft.copy(number = 7)
        assertEquals(InvoiceStatus.DRAFT, InvoiceCalc.status(draft, credited = false, today))
        assertEquals(InvoiceStatus.UNPAID, InvoiceCalc.status(issued, credited = false, today))
        // Forfallsdagen er siste dag i fristen, så fakturaen er først forfalt dagen etter.
        assertEquals(InvoiceStatus.UNPAID, InvoiceCalc.status(issued, credited = false, today.plusDays(14)))
        assertEquals(InvoiceStatus.OVERDUE, InvoiceCalc.status(issued, credited = false, today.plusDays(15)))
        assertEquals(InvoiceStatus.PAID, InvoiceCalc.status(issued.copy(paidDate = today), credited = false, today.plusDays(30)))
        assertEquals(InvoiceStatus.CREDITED, InvoiceCalc.status(issued, credited = true, today.plusDays(30)))
        assertEquals(InvoiceStatus.CREDIT_NOTE, InvoiceCalc.status(issued.copy(creditedInvoiceId = 3), credited = false, today))
    }

    @Test
    fun formatsOrganisationAndAccountNumbers() {
        assertEquals("912 345 678", Formats.orgNumber("912345678"))
        assertEquals("912 345 678", Formats.orgNumber("912 345 678"))
        assertEquals("12345", Formats.orgNumber(" 12345 "))
        assertEquals("1234.56.78903", Formats.bankAccount("12345678903"))
        assertEquals("1234 56", Formats.bankAccount("1234 56"))
    }
}
