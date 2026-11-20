package app.oikonomos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrencyDetectorTest {
    private fun detect(vararg rows: String) = CurrencyDetector.detect(rows.toList())

    @Test
    fun norwegianReceiptsHaveNoForeignCurrency() {
        assertNull(detect("KIWI 302 MAJORSTUEN", "Totalt 428,00", "Bankkort 428,00"))
        assertNull(detect("Sum kr 199,00"))
        assertNull(detect("TOTAL NOK 199,00", "Tilsvarer EUR 17,20"))
    }

    @Test
    fun shopNamesAndWordsThatLookLikeCurrenciesAreIgnored() {
        assertNull(detect("EURO SKO NORGE AS", "Totalt 899,00"))
        assertNull(detect("EUROPRIS", "TRY OUR APP", "Totalt 59,90"))
        assertNull(detect("Vare 5 S", "Totalt 59,90"))
    }

    @Test
    fun readsCurrencyCodesAndSigns() {
        assertEquals("EUR", detect("TOTAL EUR 12,50"))
        assertEquals("EUR", detect("Summe 12,50 €"))
        assertEquals("EUR", detect("Totale 12,50 euro"))
        assertEquals("USD", detect("TOTAL $1,234.56"))
        assertEquals("GBP", detect("Total £12.50"))
        assertEquals("SEK", detect("Att betala 199,00 SEK", "Moms 25% 39,80 kr"))
    }

    @Test
    fun readsEgyptianPoundsInArabicAndLatin() {
        assertEquals("EGP", detect("الإجمالي 75,00 ج.م"))
        assertEquals("EGP", detect("المطلوب 75 جنيه"))
        assertEquals("EGP", detect("Total 150 LE"))
        assertEquals("EGP", detect("TOTAL EGP 150.00"))
    }

    @Test
    fun arabicWordsWithTheSameLettersAreNotACurrency() {
        assertNull(detect("جميع الاسعار تشمل الضريبة", "الاجمالي 75,00"))
    }

    @Test
    fun yenSignMeansYenUnlessTheReceiptIsChinese() {
        assertEquals("JPY", detect("合計 ¥3,480"))
        assertEquals("JPY", detect("合計 3,480円"))
        assertEquals("CNY", detect("合计 ¥128.00 元"))
    }

    @Test
    fun scriptDecidesWhenNoCurrencyIsPrinted() {
        assertEquals("EUR", detect("ΤΑΒΕΡΝΑ Ο ΠΛΑΤΑΝΟΣ", "ΣΥΝΟΛΟ 27,50"))
        assertEquals("JPY", detect("ファミリーマート", "合計 308"))
        assertNull(detect("مطعم النيل", "الاجمالي 75,00"))
    }
}
