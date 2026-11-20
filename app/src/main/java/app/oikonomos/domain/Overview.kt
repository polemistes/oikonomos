package app.oikonomos.domain

import app.oikonomos.data.VoucherType
import app.oikonomos.data.VoucherWithDetails

data class CategorySum(val account: String, val name: String, val count: Int, val sumOre: Long)

/** MVA for én termin på to måneder: 1 er januar og februar, 6 er november og desember. */
data class VatTerm(val term: Int, val outgoingOre: Long, val incomingOre: Long) {
    val payableOre: Long get() = outgoingOre - incomingOre
}

/** Inntekter og kostnader uten MVA for ett prosjekt. */
data class ProjectSum(val name: String, val incomeOre: Long, val expensesOre: Long) {
    val resultOre: Long get() = incomeOre - expensesOre
}

/** Tallene på oversiktsskjermen, regnet ut fra bilagene i ett år. */
object Overview {
    /** Sum uten MVA per kategori, sortert på kontonummer. Bilag uten kategori samles under tomt navn til slutt. */
    fun byCategory(vouchers: List<VoucherWithDetails>, type: VoucherType): List<CategorySum> =
        vouchers.filter { it.voucher.type == type }
            .groupBy { it.category }
            .map { (category, items) ->
                CategorySum(
                    account = category?.account.orEmpty(),
                    name = category?.name.orEmpty(),
                    count = items.size,
                    sumOre = items.sumOf { it.voucher.netOre },
                )
            }
            .sortedWith(compareBy({ it.name.isEmpty() }, { it.account }, { it.name }))

    /** Sum per prosjekt, i navnerekkefølge. Bilag uten prosjekt er ikke med. */
    fun byProject(vouchers: List<VoucherWithDetails>): List<ProjectSum> =
        vouchers.filter { it.project != null }
            .groupBy { it.project!! }
            .map { (project, items) ->
                ProjectSum(
                    name = project.name,
                    incomeOre = items.filter { it.voucher.type == VoucherType.INCOME }.sumOf { it.voucher.netOre },
                    expensesOre = items.filter { it.voucher.type == VoucherType.EXPENSE }.sumOf { it.voucher.netOre },
                )
            }
            .sortedBy { it.name.lowercase() }

    /** Alle seks terminer, også de uten bilag. */
    fun vatTerms(vouchers: List<VoucherWithDetails>): List<VatTerm> {
        val byTerm = vouchers.groupBy { (it.voucher.date.monthValue + 1) / 2 }
        return (1..6).map { term ->
            val items = byTerm[term].orEmpty()
            VatTerm(
                term = term,
                outgoingOre = items.filter { it.voucher.type == VoucherType.INCOME }.sumOf { it.voucher.vatOre },
                incomingOre = items.filter { it.voucher.type == VoucherType.EXPENSE }.sumOf { it.voucher.vatOre },
            )
        }
    }
}
