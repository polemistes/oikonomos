package app.oikonomos.domain

import java.time.DateTimeException
import java.time.LocalDate

/** En tekstlinje fra tekstgjenkjenningen, med plassering i bildet. */
data class OcrLine(val text: String, val left: Int, val top: Int, val bottom: Int)

data class ReceiptSuggestion(
    /** Den mest sannsynlige leverandøren, altså første kandidat. */
    val vendor: String?,
    val date: LocalDate?,
    val amountOre: Long?,
    /** Linjene fra toppen av kvitteringen som kan være firmanavnet, i rekkefølge, til brukeren å velge blant. */
    val vendorCandidates: List<String> = emptyList(),
) {
    val isEmpty get() = vendor == null && date == null && amountOre == null
}

/** Gjetter leverandør, dato og totalbeløp fra teksten på en kvittering. Resultatet er et forslag brukeren må se over. */
object ReceiptParser {
    // Tekstgjenkjenningen setter ofte inn mellomrom etter skilletegn («14.11. 2026», «567, 50»), så det tillates.
    private val numericDate = Regex("(?<!\\d)(\\d{1,2})[./-] ?(\\d{1,2})[./-] ?(\\d{4}|\\d{2})(?!\\d)")
    private val isoDate = Regex("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)")
    private val amount = Regex("(?<![\\d.,])(\\d{1,3}(?:\\.\\d{3})+|\\d+)[.,] ?(\\d{2})(?!\\d)")
    private val amountSpaceGrouped = Regex("(?<![\\d.,])(\\d{1,3}(?:[ .]\\d{3})+|\\d+)[.,] ?(\\d{2})(?!\\d)")
    private val totalKeywords = listOf("å betale", "a betale", "til betaling", "totalt", "total", "sum", "beløp", "belop")
    private val headerWords = setOf("kvittering", "salgskvittering", "kopi", "faktura", "kassalapp", "kontantnota", "bong")

    /** Linjer som står øverst på kvitteringer, men som aldri er firmanavnet. */
    private val notAName = Regex("org\\.? ?nr|orgnr|foretaksregisteret|\\btlf\\b|telefon|www\\.|@|https?:", RegexOption.IGNORE_CASE)
    private const val MAX_VENDOR_CANDIDATES = 6

    fun parse(lines: List<OcrLine>, today: LocalDate): ReceiptSuggestion {
        val rows = toRows(lines)
        val vendors = vendorCandidates(rows)
        return ReceiptSuggestion(vendors.firstOrNull(), findDate(rows, today), findTotal(rows), vendors)
    }

    /**
     * Kvitteringer er satt opp i kolonner, og tekstgjenkjenningen gir ofte «Sum» og «199,00» som
     * hver sin linje. Linjer som står på samme høyde slås derfor sammen til én rad.
     */
    internal fun toRows(lines: List<OcrLine>): List<String> {
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in lines.sortedBy { it.top + it.bottom }) {
            val center = (line.top + line.bottom) / 2
            val current = rows.lastOrNull()?.takeIf { center in it.first().top..it.first().bottom }
            if (current != null) current += line else rows += mutableListOf(line)
        }
        return rows.map { row -> row.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }
    }

    /**
     * Firmanavnet står nesten alltid i toppen av kvitteringen, før varelinjene. Kandidatene er derfor
     * tekstlinjene ovenfra og ned til første linje med et beløp, uten overskrifter som «Salgskvittering»,
     * datoer og linjer med organisasjonsnummer, telefon eller nettadresse.
     */
    private fun vendorCandidates(rows: List<String>): List<String> =
        rows.take(12)
            .takeWhile { amountsIn(it, amount).isEmpty() }
            .map { it.trim() }
            .filter { row ->
                val firstWord = row.takeWhile { it.isLetter() }.lowercase()
                row.count { it.isLetter() } >= 3 &&
                    firstWord !in headerWords &&
                    !notAName.containsMatchIn(row) &&
                    !numericDate.containsMatchIn(row) && !isoDate.containsMatchIn(row)
            }
            .map { it.take(60) }
            .distinct()
            .take(MAX_VENDOR_CANDIDATES)

    private fun findDate(rows: List<String>, today: LocalDate): LocalDate? {
        val earliest = today.minusYears(10)
        val latest = today.plusDays(1)
        for (row in rows) {
            val candidates = isoDate.findAll(row).map { m ->
                dateOrNull(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            } + numericDate.findAll(row).map { m ->
                val year = m.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
                dateOrNull(year, m.groupValues[2].toInt(), m.groupValues[1].toInt())
            }
            candidates.firstOrNull { it != null && it in earliest..latest }?.let { return it }
        }
        return null
    }

    private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? =
        try {
            LocalDate.of(year, month, day)
        } catch (_: DateTimeException) {
            null
        }

    private fun findTotal(rows: List<String>): Long? {
        val totalRows = rows.filter { row ->
            val lower = row.lowercase()
            val exVat = "eks" in lower || (("mva" in lower || "moms" in lower) && "inkl" !in lower)
            totalKeywords.any { it in lower } && "subtotal" !in lower && !exVat
        }
        // I en totalrad er «1 199,00» ett beløp. Ellers kan «1» være et antall foran en pris.
        totalRows.flatMap { amountsIn(it, amountSpaceGrouped) }.maxOrNull()?.let { return it }
        return rows.flatMap { amountsIn(it, amount) }.maxOrNull()
    }

    private fun amountsIn(row: String, pattern: Regex): List<Long> =
        pattern.findAll(row).mapNotNull { Money.parse(it.value) }.filter { it > 0 }.toList()
}
