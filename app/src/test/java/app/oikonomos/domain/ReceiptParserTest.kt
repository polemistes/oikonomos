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
        assertEquals(listOf("Kunstnernes Hus", "Butikk og kafé"), result.vendorCandidates)
        assertEquals("Kunstnernes Hus", result.vendor)
    }

    @Test
    fun vendorCandidatesAreEmptyWhenTheReceiptStartsWithItemLines() {
        val result = ReceiptParser.parse(lines(listOf("Melk", "19,90"), listOf("Brød", "34,50")), today)
        assertNull(result.vendor)
        assertTrue(result.vendorCandidates.isEmpty())
    }

    @Test
    fun usesPaymentLineWhenSumIsUnreadableAndIgnoresNoiseFromTheCardSlip() {
        // Etter en ekte kafékvittering, slik den ble lest: «Sum» var utvisket, datoen sto med månedsnavn,
        // og den falmede kortdelen nederst ga et tall som så ut som et beløp. Navn og numre er byttet ut.
        val receipt = listOf(
            OcrLine("Kaféen SØR drevet av Eksempel", 116, 294, 346),
            OcrLine("MVA nUMmer: 912 345 678 MV", 166, 458, 494),
            OcrLine("lid: Aug. 26, 2026 0/:44", 116, 618, 657),
            OcrLine("Salgskvittering", 299, 177, 240),
            OcrLine("Kvittering-ID: F25A0000", 105, 950, 1001),
            OcrLine("1 Farris Bris Lemon", 121, 1217, 1279),
            OcrLine("1x Pant kr 2", 154, 1294, 1339),
            OcrLine("1 Kaffe", 115, 1359, 1407),
            OcrLine("mva.", 92, 1657, 1686),
            OcrLine("15%", 91, 1718, 1757),
            OcrLine("Kort", 83, 1939, 1980),
            OcrLine("Netto", 286, 1654, 1694),
            OcrLine("84,35", 279, 1725, 1772),
            OcrLine("0% 2,00 0,00", 88, 1790, 1855),
            OcrLine("25/08/2:)2660/ 44", 111, 2361, 2413),
            OcrLine("Transaksjonsreferanse:", 79, 2012, 2082),
            OcrLine("123456789012", 79, 2077, 2131),
            OcrLine("Beløp", 497, 1663, 1711),
            OcrLine("15% 49,00", 616, 1233, 1279),
            OcrLine("12,65", 497, 1734, 1781),
            OcrLine("Rel 512,34 068KCI", 112, 2657, 2733),
            OcrLine("15% 48,00", 615, 1381, 1426),
            OcrLine("0% 2,00", 639, 1311, 1354),
            OcrLine("99,00 NOK", 628, 1523, 1573),
            OcrLine("Brutto", 744, 1675, 1717),
            OcrLine("97,00", 754, 1744, 1796),
            OcrLine("2,00", 780, 1822, 1868),
            OcrLine("99,00 NOK", 622, 1963, 2016),
        )
        val result = ReceiptParser.parse(receipt, today)
        assertEquals("Kaféen SØR drevet av Eksempel", result.vendor)
        // «MVA nummer: …» og «Kvittering-ID: …» står øverst, men er ikke firmanavn.
        assertEquals(listOf("Kaféen SØR drevet av Eksempel"), result.vendorCandidates)
        assertEquals(LocalDate.of(2026, 8, 26), result.date)
        assertEquals(9900L, result.amountOre)
    }

    @Test
    fun readsDatesWrittenWithMonthNames() {
        fun dateOf(text: String) = ReceiptParser.parse(lines(listOf("Butikk"), listOf(text)), today).date
        assertEquals(LocalDate.of(2026, 8, 26), dateOf("Tid: Aug. 26, 2026 07:44"))
        assertEquals(LocalDate.of(2026, 8, 26), dateOf("26. aug. 2026"))
        assertEquals(LocalDate.of(2026, 8, 26), dateOf("26 August 2026 kl. 07.44"))
        assertEquals(LocalDate.of(2026, 3, 3), dateOf("Dato: 3. mars 2026"))
        assertEquals(LocalDate.of(2026, 5, 17), dateOf("May 17th, 2026"))
        assertEquals(LocalDate.of(2026, 10, 1), dateOf("1 okt 2026"))
        // «design» og «marked» begynner som månedsnavn, men er det ikke.
        assertNull(dateOf("Design 12, 2026"))
        assertNull(dateOf("Marked 3 2026"))
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

    @Test
    fun readsOrgNumberAndLeavesOutTheUsersOwn() {
        val invoice = lines(
            listOf("Rammeverkstedet AS"),
            listOf("Org.nr 974 760 673 MVA"),
            listOf("Kunde: Mitt Foretak, org.nr 923 609 016"),
            listOf("Fakturadato 03.11.2026"),
            listOf("Å betale", "1 250,00"),
        )
        assertEquals("974760673", ReceiptParser.parse(invoice, today, ownOrgNumber = "923609016").orgNumber)
        assertNull(ReceiptParser.parse(invoice, today).currency)
    }

    @Test
    fun orgNumberWithWrongCheckDigitIsNotSuggested() {
        val receipt = lines(listOf("KUNSTNERNES HUS BUTIKK"), listOf("Org.nr 987 654 321 MVA"), listOf("Totalt", "428,00"))
        assertNull(ReceiptParser.parse(receipt, today).orgNumber)
    }

    // De utenlandske kvitteringene under er satt opp etter vanlige oppsett, ikke lest av fra ekte kvitteringer.

    @Test
    fun readsGreekReceiptWithoutCurrencySign() {
        val receipt = lines(
            listOf("ΤΑΒΕΡΝΑ Ο ΠΛΑΤΑΝΟΣ"),
            listOf("ΑΦΜ 094012345 ΔΟΥ ΑΘΗΝΩΝ"),
            listOf("ΤΗΛ 210 1234567"),
            listOf("14/11/2026 21:15"),
            listOf("ΧΩΡΙΑΤΙΚΗ", "8,50"),
            listOf("ΜΟΥΣΑΚΑΣ", "12,00"),
            listOf("ΚΡΑΣΙ 500ML", "7,00"),
            listOf("ΜΕΡΙΚΟ ΣΥΝΟΛΟ", "27,50"),
            listOf("ΦΠΑ 13%", "3,16"),
            listOf("ΣΥΝΟΛΟ", "27,50"),
            listOf("ΜΕΤΡΗΤΑ", "30,00"),
            listOf("ΡΕΣΤΑ", "2,50"),
        )
        val result = ReceiptParser.parse(receipt, today)
        assertEquals("ΤΑΒΕΡΝΑ Ο ΠΛΑΤΑΝΟΣ", result.vendor)
        assertEquals(LocalDate.of(2026, 11, 14), result.date)
        assertEquals("EUR", result.currency)
        assertEquals(2750L, result.amountOre)
        assertNull(result.orgNumber)
    }

    @Test
    fun readsGreekTotalWrittenWithAccentsAndEuroSign() {
        val receipt = lines(listOf("Καφενείο"), listOf("Σύνολο", "4,20 €"), listOf("Μετρητά", "5,00 €"), listOf("Ρέστα", "0,80 €"))
        val result = ReceiptParser.parse(receipt, today)
        assertEquals("EUR", result.currency)
        assertEquals(420L, result.amountOre)
    }

    @Test
    fun readsJapaneseReceiptInWholeYen() {
        val receipt = lines(
            listOf("ファミリーマート 渋谷店"),
            listOf("東京都渋谷区道玄坂1-2-3"),
            listOf("電話 03-1234-5678"),
            listOf("2026年11月14日(土) 12:34"),
            listOf("おにぎり 鮭", "¥160"),
            listOf("幕の内弁当", "￥１，２８０"),
            listOf("小計", "¥1,440"),
            listOf("(内消費税等 8%", "¥106)"),
            listOf("合計", "¥1,440"),
            listOf("お預り", "¥5,000"),
            listOf("お釣り", "¥3,560"),
        )
        val result = ReceiptParser.parse(receipt, today)
        assertEquals("ファミリーマート 渋谷店", result.vendor)
        assertEquals(LocalDate.of(2026, 11, 14), result.date)
        assertEquals("JPY", result.currency)
        assertEquals(144000L, result.amountOre)
    }

    @Test
    fun japaneseReceiptWithoutTotalRowUsesLargestPriceThatIsNotCashHandedOver() {
        val receipt = lines(
            listOf("タクシー"),
            listOf("R8.11.14"),
            listOf("運賃", "2,300円"),
            listOf("お預り", "5,000円"),
            listOf("お釣り", "2,700円"),
        )
        val result = ReceiptParser.parse(receipt, today)
        assertEquals(LocalDate.of(2026, 11, 14), result.date)
        assertEquals(230000L, result.amountOre)
    }

    @Test
    fun readsEraDateWrittenInFull() {
        val receipt = lines(listOf("書店"), listOf("令和8年3月5日"), listOf("合計", "1,980円"))
        assertEquals(LocalDate.of(2026, 3, 5), ReceiptParser.parse(receipt, today).date)
    }

    @Test
    fun readsEgyptianReceiptWithArabicDigits() {
        val receipt = lines(
            listOf("مطعم النيل"),
            listOf("القاهرة - شارع التحرير"),
            listOf("التاريخ: ١٤/١١/٢٠٢٦"),
            listOf("كشري", "٤٥٫٠٠"),
            listOf("عصير", "٣٠٫٠٠"),
            listOf("الإجمالى", "٧٥٫٠٠ ج.م"),
            listOf("المدفوع", "١٠٠٫٠٠"),
        )
        val result = ReceiptParser.parse(receipt, today)
        assertEquals("مطعم النيل", result.vendor)
        assertEquals(LocalDate.of(2026, 11, 14), result.date)
        assertEquals("EGP", result.currency)
        assertEquals(7500L, result.amountOre)
    }

    @Test
    fun readsForeignTotalWrittenWithoutDecimals() {
        val receipt = lines(listOf("Nile Souvenirs"), listOf("12-11-2026"), listOf("Papyrus x2", "150"), listOf("Total", "150 EGP"))
        val result = ReceiptParser.parse(receipt, today)
        assertEquals("EGP", result.currency)
        assertEquals(15000L, result.amountOre)
    }

    @Test
    fun readsAmericanReceiptWithCommaGroupedAmountAndMonthFirst() {
        val receipt = lines(
            listOf("ART SUPPLY WAREHOUSE"),
            listOf("11/14/2026 3:12 PM"),
            listOf("Easel", "$1,150.00"),
            listOf("Subtotal", "$1,150.00"),
            listOf("Sales Tax", "$84.56"),
            listOf("TOTAL", "$1,234.56"),
        )
        val result = ReceiptParser.parse(receipt, today)
        assertEquals("ART SUPPLY WAREHOUSE", result.vendor)
        assertEquals(LocalDate.of(2026, 11, 14), result.date)
        assertEquals("USD", result.currency)
        assertEquals(123456L, result.amountOre)
    }

    @Test
    fun readsThaiReceiptDatedInBuddhistEra() {
        val receipt = lines(listOf("ร้านกาแฟ"), listOf("14/11/2569"), listOf("รวมทั้งสิ้น", "185.00"))
        val result = ReceiptParser.parse(receipt, today)
        assertEquals(LocalDate.of(2026, 11, 14), result.date)
        assertEquals("THB", result.currency)
        assertEquals(18500L, result.amountOre)
    }

    @Test
    fun aTimeWithSecondsIsNotReadAsAnAmericanDate() {
        val receipt = lines(listOf("Butikk"), listOf("kl 10.25.26"), listOf("Totalt", "59,90"))
        assertNull(ReceiptParser.parse(receipt, today).date)
    }

    @Test
    fun readsArabicReceiptAsTheTwoEnginesDeliveredIt() {
        // De sju første linjene er slik Tesseract leste en konstruert testkvittering: beløpet på sumlinja ble
        // «0», og usynlige retningstegn er satt inn rundt datoen. De fem siste er tallene slik Googles leser
        // legger dem til, som egne ord på samme rad.
        val receipt = listOf(
            OcrLine("مطعم النيل", 690, 70, 110),
            OcrLine("القاهرة - شارع التحرير", 570, 126, 166),
            OcrLine("\u200E14.11.2026\u200F", 650, 182, 222),
            OcrLine("كشري 45.00", 50, 238, 278),
            OcrLine("عصير 30.00", 50, 294, 334),
            OcrLine("الإجمالي 0 ج.م", 50, 350, 390),
            OcrLine("المدفوع 100.00", 50, 406, 446),
            OcrLine("14.11.2026", 655, 186, 220),
            OcrLine("45.00", 50, 242, 276),
            OcrLine("30.00", 50, 298, 332),
            OcrLine("75.00", 108, 354, 388),
            OcrLine("100.00", 50, 410, 444),
        )
        val result = ReceiptParser.parse(receipt, today)
        assertEquals("مطعم النيل", result.vendor)
        assertEquals(LocalDate.of(2026, 11, 14), result.date)
        assertEquals("EGP", result.currency)
        assertEquals(7500L, result.amountOre)
    }

    @Test
    fun cashHandedOverIsNeverTheLargestAmount() {
        val receipt = lines(listOf("Cafe Luxor"), listOf("Tea", "45.00"), listOf("Cash tendered", "100.00"), listOf("Change", "55.00"))
        assertEquals(4500L, ReceiptParser.parse(receipt, today).amountOre)
    }
}
