package app.oikonomos.domain

import app.oikonomos.data.Category
import app.oikonomos.data.Project
import app.oikonomos.data.Voucher
import app.oikonomos.data.VoucherType
import app.oikonomos.data.VoucherWithDetails
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class OverviewTest {
    private val sales = Category(id = 1, name = "Salg og honorar", account = "3200", type = VoucherType.INCOME)
    private val materials = Category(id = 2, name = "Materialer og varekjøp", account = "4000", type = VoucherType.EXPENSE)
    private val travel = Category(id = 3, name = "Reise", account = "7140", type = VoucherType.EXPENSE)

    private fun voucher(type: VoucherType, month: Int, amountOre: Long, category: Category?, vatOre: Long = 0) =
        VoucherWithDetails(
            voucher = Voucher(
                year = 2026, number = 1, type = type, date = LocalDate.of(2026, month, 15),
                amountOre = amountOre, vatOre = vatOre, categoryId = category?.id, createdAt = 0,
            ),
            category = category,
            attachments = emptyList(),
        )

    @Test
    fun sumsPerCategoryInAccountOrderWithUncategorisedLast() {
        val vouchers = listOf(
            voucher(VoucherType.EXPENSE, 1, 5000, travel),
            voucher(VoucherType.EXPENSE, 2, 10000, null),
            voucher(VoucherType.EXPENSE, 3, 20000, materials),
            voucher(VoucherType.EXPENSE, 4, 2500, materials),
            voucher(VoucherType.INCOME, 5, 99999, sales),
        )
        assertEquals(
            listOf(
                CategorySum("4000", "Materialer og varekjøp", 2, 22500),
                CategorySum("7140", "Reise", 1, 5000),
                CategorySum("", "", 1, 10000),
            ),
            Overview.byCategory(vouchers, VoucherType.EXPENSE),
        )
        assertEquals(listOf(CategorySum("3200", "Salg og honorar", 1, 99999)), Overview.byCategory(vouchers, VoucherType.INCOME))
    }

    @Test
    fun creditNoteReducesTheIncomeOfItsCategory() {
        val vouchers = listOf(
            voucher(VoucherType.INCOME, 5, 100000, sales),
            voucher(VoucherType.INCOME, 6, -100000, sales),
            voucher(VoucherType.INCOME, 6, 80000, sales),
        )
        assertEquals(listOf(CategorySum("3200", "Salg og honorar", 3, 80000)), Overview.byCategory(vouchers, VoucherType.INCOME))
    }

    @Test
    fun categorySumsExcludeVat() {
        val vouchers = listOf(voucher(VoucherType.INCOME, 5, 125000, sales, vatOre = 25000))
        assertEquals(listOf(CategorySum("3200", "Salg og honorar", 1, 100000)), Overview.byCategory(vouchers, VoucherType.INCOME))
    }

    @Test
    fun sumsIncomeAndCostsPerProjectAndLeavesOutVouchersWithoutProject() {
        val exhibition = Project(id = 1, name = "Utstilling 2027")
        val development = Project(id = 2, name = "Kunstnerisk utvikling")
        val vouchers = listOf(
            voucher(VoucherType.INCOME, 5, 500000, sales).copy(project = exhibition),
            voucher(VoucherType.EXPENSE, 4, 120000, materials).copy(project = exhibition),
            voucher(VoucherType.EXPENSE, 6, 80000, travel).copy(project = development),
            voucher(VoucherType.EXPENSE, 7, 9900, materials),
        )
        val sums = Overview.byProject(vouchers)
        assertEquals(
            listOf(ProjectSum("Kunstnerisk utvikling", 0, 80000), ProjectSum("Utstilling 2027", 500000, 120000)),
            sums,
        )
        assertEquals(380000L, sums[1].resultOre)
    }

    @Test
    fun vatIsSplitIntoSixTwoMonthTerms() {
        val terms = Overview.vatTerms(
            listOf(
                voucher(VoucherType.INCOME, 1, 12500, sales, vatOre = 2500),
                voucher(VoucherType.EXPENSE, 2, 5000, materials, vatOre = 1000),
                voucher(VoucherType.INCOME, 3, 25000, sales, vatOre = 5000),
                voucher(VoucherType.EXPENSE, 12, 10000, materials, vatOre = 2000),
            ),
        )
        assertEquals(6, terms.size)
        assertEquals(VatTerm(1, outgoingOre = 2500, incomingOre = 1000), terms[0])
        assertEquals(1500L, terms[0].payableOre)
        assertEquals(VatTerm(2, outgoingOre = 5000, incomingOre = 0), terms[1])
        assertEquals(VatTerm(3, 0, 0), terms[2])
        assertEquals(-2000L, terms[5].payableOre)
    }
}
