package app.oikonomos.domain

import app.oikonomos.data.Voucher
import app.oikonomos.data.VoucherType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class VoucherRulesTest {
    private val pending = Voucher(
        year = 2026, number = null, type = VoucherType.EXPENSE, date = LocalDate.of(2026, 7, 14), amountOre = 0, createdAt = 0,
    )
    private val booked = pending.copy(number = 7, amountOre = 45000)

    @Test
    fun undecidedReceiptCanBeChangedBookedAndDeletedButHasNoNumber() {
        val permissions = VoucherRules.permissions(pending)
        assertTrue(permissions.editAll)
        assertTrue(permissions.book)
        assertTrue(permissions.delete)
        assertFalse(permissions.cancel)
        assertEquals("", pending.label)
        assertFalse(pending.counts)
    }

    @Test
    fun bookedVoucherCanBeCorrectedAndAnnulledButNeverDeleted() {
        val permissions = VoucherRules.permissions(booked)
        assertTrue(permissions.editAll)
        assertTrue(permissions.cancel)
        assertFalse(permissions.delete)
        assertFalse(permissions.book)
        assertEquals("2026-0007", booked.label)
        assertTrue(booked.counts)
    }

    @Test
    fun onlyTheLastVoucherOfTheYearCanBeUnbooked() {
        assertTrue(VoucherRules.permissions(booked, lastNumberInYear = 7).unbook)
        // Et tidligere bilag ville etterlatt et hull i nummerrekken.
        assertFalse(VoucherRules.permissions(booked, lastNumberInYear = 8).unbook)
        assertFalse(VoucherRules.permissions(booked).unbook)
        assertFalse(VoucherRules.permissions(pending, lastNumberInYear = 7).unbook)
        assertFalse(VoucherRules.permissions(booked.copy(invoiceId = 3), lastNumberInYear = 7).unbook)
        assertFalse(VoucherRules.permissions(booked.copy(cancelledAt = 1000), lastNumberInYear = 7).unbook)
    }

    @Test
    fun voucherFromInvoiceOnlyAllowsCategoryAndDescription() {
        val permissions = VoucherRules.permissions(booked.copy(invoiceId = 3))
        assertFalse(permissions.editAll)
        assertTrue(permissions.editCategory)
        assertTrue(permissions.editDescription)
        assertFalse(permissions.delete)
        // En faktura rettes med kreditnota, ikke ved å annullere bilaget.
        assertFalse(permissions.cancel)
    }

    @Test
    fun annulledVoucherKeepsItsNumberButIsReadOnlyAndNotCounted() {
        val cancelled = booked.copy(cancelledAt = 1000, cancelReason = "Feil beløp")
        assertEquals(VoucherPermissions.NONE, VoucherRules.permissions(cancelled))
        assertFalse(VoucherRules.permissions(cancelled).anyEdit)
        assertEquals("2026-0007", cancelled.label)
        assertFalse(cancelled.counts)
    }
}
