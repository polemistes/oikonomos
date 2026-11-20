package app.oikonomos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReceiptParserTest {
    private val today = LocalDate.of(2026, 11, 19)

    /** Hver rad er 20 piksler høy. Tekster på samme rad får ulik venstrekant. */
    private fun lines(vararg rows: List<String>): List<OcrLine> =
        rows.flatMapIndexed { rowIndex, cells ->
            cells.mapIndexed { column, text -> OcrLine(text, column * 300, rowIndex * 30, rowIndex * 30 + 20) }
        }

    @Test
    fun readsShopReceiptWithLabelsAndAmountsInSeparateColumns() {
        val receipt = lines(
            listOf("KUNSTNERNES HUS BUTIKK"),
            listOf("Org.nr 987 654 321 MVA"),
            listOf("19.11.2026 14:32"),
            listOf("Akrylmaling 500ml", "249,00"),
            listOf("Pensel nr 8", "2 x 89,50"),
            listOf("Sum eks. mva", "342,40"),
            listOf("MVA 25%", "85,60"),
            listOf("Totalt", "428,00"),
            listOf("Bankkort", "428,00"),
        )
        val result = ReceiptParser.parse(receipt, today)
        assertEquals("KUNSTNERNES HUS BUTIKK", result.vendor)
        assertEquals(LocalDate.of(2026, 11, 19), result.date)
        assertEquals(42800L, result.amountOre)
    }

    @Test
    fun mergesColumnsEvenWhenOcrReturnsThemOutOfOrder() {
        val shuffled = listOf(
            OcrLine("1 199,00", 400, 62, 80),
            OcrLine("Rammeverkstedet AS", 0, 0, 20),
            OcrLine("Å betale", 0, 60, 80),
            OcrLine("2026-03-04", 0, 30, 50),
        )
        val result = ReceiptParser.parse(shuffled, today)
        assertEquals("Rammeverkstedet AS", result.vendor)
        assertEquals(LocalDate.of(2026, 3, 4), result.date)
        assertEquals(119900L, result.amountOre)
    }

    @Test
    fun readsDateWhereOcrInsertedSpaceAfterSeparator() {
        val receipt = lines(listOf("Butikk"), listOf("14.11. 2026 13:42"))
        assertEquals(LocalDate.of(2026, 11, 14), ReceiptParser.parse(receipt, today).date)
    }

    @Test
    fun readsAmountsWhereOcrInsertedSpaceAfterDecimalComma() {
        // Linjene slik tekstgjenkjenningen leverte dem for en testkvittering: etikettene først, så beløpene.
        val receipt = listOf(
            OcrLine("PAPIR & PENSEL AS", 178, 58, 100),
            OcrLine("Org.nr 912 345 678 MVA", 200, 135, 170),
            OcrLine("Akvarellpapir A3", 61, 323, 364),
            OcrLine("14.11.2026 13:42", 272, 194, 224),
            OcrLine("Sum eks. mva", 62, 585, 620),
            OcrLine("MVA 25%", 62, 657, 686),
            OcrLine("TOTALT", 60, 726, 756),
            OcrLine("Bankkort", 63, 847, 876),
            OcrLine("349,00", 706, 326, 360),
            OcrLine("454,00", 715, 585, 622),
            OcrLine("113,50", 707, 657, 691),
            OcrLine("567, 50", 706, 726, 762),
            OcrLine("567, 50", 706, 847, 881),
        )
        val result = ReceiptParser.parse(receipt, today)
        assertEquals("PAPIR & PENSEL AS", result.vendor)
        assertEquals(LocalDate.of(2026, 11, 14), result.date)
        assertEquals(56750L, result.amountOre)
    }

    @Test
    fun skipsHeaderWordsAndFallsBackToLargestAmount() {
        val receipt = lines(
            listOf("KVITTERING"),
            listOf("Papirhandelen"),
            listOf("03/02/26"),
            listOf("Skisseblokk A3", "1 189,00"),
            listOf("Blyanter", "45,50"),
        )
        val result = ReceiptParser.parse(receipt, today)
        assertEquals("Papirhandelen", result.vendor)
        assertEquals(LocalDate.of(2026, 2, 3), result.date)
        // Uten totalrad leses «1 189,00» som antall 1 og pris 189,00.
        assertEquals(18900L, result.amountOre)
    }

    @Test
    fun offersTheTopLinesAsVendorCandidatesWithoutHeadersAndContactLines() {
        val receipt = lines(
            listOf("SALGSKVITTERING NR 4711"),
            listOf("Kunstnernes Hus"),
            listOf("Butikk og kafé"),
            listOf("Wergelandsveien 17, 0167 Oslo"),
            listOf("Org.nr. 987 654 321 MVA"),
            listOf("Tlf 22 85 34 10"),
            listOf("14.11.2026 13:42"),
            listOf("Akrylmaling 500ml", "249,00"),
            listOf("Takk for handelen"),
        )
        val result = ReceiptParser.parse(receipt, today)
        assertEquals(listOf("Kunstnernes Hus", "Butikk og kafé", "Wergelandsveien 17, 0167 Oslo"), result.vendorCandidates)
        assertEquals("Kunstnernes Hus", result.vendor)
    }

    @Test
    fun vendorCandidatesAreEmptyWhenTheReceiptStartsWithItemLines() {
        val result = ReceiptParser.parse(lines(listOf("Melk", "19,90"), listOf("Brød", "34,50")), today)
        assertNull(result.vendor)
        assertTrue(result.vendorCandidates.isEmpty())
    }

    @Test
    fun ignoresImpossibleAndFutureDates() {
        val receipt = lines(
            listOf("Butikk"),
            listOf("Gyldig til 31.12.2030"),
            listOf("Ref 45.13.2026"),
            listOf("Kjøpt 01.10.2026"),
        )
        assertEquals(LocalDate.of(2026, 10, 1), ReceiptParser.parse(receipt, today).date)
    }

    @Test
    fun givesEmptySuggestionForUnreadableText() {
        val result = ReceiptParser.parse(lines(listOf("12"), listOf("--")), today)
        assertNull(result.vendor)
        assertNull(result.date)
        assertNull(result.amountOre)
        assertTrue(result.isEmpty)
    }
}
