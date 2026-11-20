package app.oikonomos.domain

import app.oikonomos.data.VoucherType
import java.time.LocalDate

data class ExportRow(
    val label: String,
    val date: LocalDate,
    val type: VoucherType,
    val counterparty: String,
    val description: String,
    val category: String,
    val account: String,
    val amountOre: Long,
    val vatRate: Int,
    val vatOre: Long,
    val attachmentNames: List<String>,
    /** Navnet på reisen bilaget hører til, tomt når det ikke hører til noen. */
    val trip: String = "",
    val project: String = "",
    /** Tom for vanlige bilag. For annullerte bilag: «Annullert» og årsaken. */
    val cancelled: Boolean = false,
    val cancelReason: String = "",
) {
    val netOre: Long get() = amountOre - vatOre
}

data class InvoiceExportRow(
    val number: Int,
    val creditNote: Boolean,
    val issueDate: LocalDate,
    val dueDate: LocalDate,
    val customer: String,
    val totalOre: Long,
    val vatOre: Long,
    val paidDate: LocalDate?,
    val status: String,
)

data class TripExportRow(
    val name: String,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val project: String,
    val note: String,
    /** Bilag i regnskapet på reisen, og kostnadene deres uten MVA. */
    val voucherCount: Int,
    val costOre: Long,
)

/** Semikolondelt CSV som åpnes riktig i norsk Excel og LibreOffice. */
object CsvExport {
    /** Settes først i filen så Excel leser den som UTF-8. */
    const val BOM = "﻿"
    private const val NEWLINE = "\r\n"

    fun vouchers(rows: List<ExportRow>, vatRegistered: Boolean): String {
        val header = buildList {
            addAll(listOf("Bilagsnr", "Dato", "Type", "Motpart", "Beskrivelse", "Kategori", "Konto", "Beløp"))
            if (vatRegistered) addAll(listOf("MVA-sats", "MVA"))
            add("Prosjekt")
            add("Reise")
            add("Vedlegg")
            add("Status")
        }
        val lines = rows.map { row ->
            buildList {
                add(text(row.label))
                add(row.date.toString())
                add(typeName(row.type))
                add(text(row.counterparty))
                add(text(row.description))
                add(text(row.category))
                add(text(row.account))
                add(Money.formatPlain(row.amountOre))
                if (vatRegistered) {
                    add(row.vatRate.toString())
                    add(Money.formatPlain(row.vatOre))
                }
                add(text(row.project))
                add(text(row.trip))
                add(text(row.attachmentNames.joinToString(", ")))
                add(if (row.cancelled) text(listOf("Annullert", row.cancelReason).filter { it.isNotBlank() }.joinToString(": ")) else "")
            }
        }
        return toCsv(listOf(header) + lines)
    }

    /** Sum uten MVA per kategori, og resultatet nederst. */
    fun summary(allRows: List<ExportRow>): String {
        val rows = allRows.filter { !it.cancelled }
        val header = listOf("Type", "Konto", "Kategori", "Antall bilag", "Sum uten MVA")
        val groups = rows.groupBy { Triple(it.type, it.account, it.category) }
            .toSortedMap(compareBy({ it.first }, { it.second }, { it.third }))
            .map { (key, items) ->
                listOf(
                    typeName(key.first), text(key.second), text(key.third),
                    items.size.toString(), Money.formatPlain(items.sumOf { it.netOre }),
                )
            }
        val income = rows.filter { it.type == VoucherType.INCOME }.sumOf { it.netOre }
        val expenses = rows.filter { it.type == VoucherType.EXPENSE }.sumOf { it.netOre }
        val totals = listOf(
            listOf("", "", "", "", ""),
            listOf("Sum inntekter", "", "", "", Money.formatPlain(income)),
            listOf("Sum utgifter", "", "", "", Money.formatPlain(expenses)),
            listOf("Resultat", "", "", "", Money.formatPlain(income - expenses)),
        )
        return toCsv(listOf(header) + groups + totals)
    }

    fun invoices(rows: List<InvoiceExportRow>): String {
        val header = listOf("Nummer", "Type", "Dato", "Forfall", "Kunde", "Beløp", "MVA", "Betalt", "Status")
        val lines = rows.map { row ->
            listOf(
                row.number.toString(),
                if (row.creditNote) "Kreditnota" else "Faktura",
                row.issueDate.toString(),
                if (row.creditNote) "" else row.dueDate.toString(),
                text(row.customer),
                Money.formatPlain(row.totalOre),
                Money.formatPlain(row.vatOre),
                row.paidDate?.toString().orEmpty(),
                row.status,
            )
        }
        return toCsv(listOf(header) + lines)
    }

    /** Reisene med formål og kostnader, som dokumentasjon på hva reisene gjaldt. */
    fun trips(rows: List<TripExportRow>): String {
        val header = listOf("Reise", "Fra", "Til", "Prosjekt", "Formål", "Antall bilag", "Kostnader uten MVA")
        val lines = rows.map { row ->
            listOf(
                text(row.name), row.fromDate.toString(), row.toDate.toString(), text(row.project), text(row.note),
                row.voucherCount.toString(), Money.formatPlain(row.costOre),
            )
        }
        return toCsv(listOf(header) + lines)
    }

    private fun typeName(type: VoucherType) = if (type == VoucherType.INCOME) "Inntekt" else "Utgift"

    /** Fritekst som begynner med et formeltegn får en apostrof foran, så regnearket ikke kjører den som formel. */
    private fun text(value: String): String =
        if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value

    private fun toCsv(rows: List<List<String>>): String =
        rows.joinToString(NEWLINE, postfix = NEWLINE) { row -> row.joinToString(";", transform = ::escape) }

    private fun escape(cell: String): String =
        if (cell.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"${cell.replace("\"", "\"\"")}\"" else cell
}
