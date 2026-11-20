package app.oikonomos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ExchangeRatesTest {
    // Slik Norges Bank svarte 20.11.2026, forkortet til to dager.
    private val header = "FREQ;Frekvens;BASE_CUR;Basisvaluta;QUOTE_CUR;Kvoteringsvaluta;TENOR;Løpetid;DECIMALS;CALCULATED;" +
        "UNIT_MULT;Multiplikator;COLLECTION;Innsamlingstidspunkt;TIME_PERIOD;OBS_VALUE"
    private val yen = "$header\n" +
        "B;Virkedag;JPY;Japanske yen;NOK;Norske kroner;SP;Spot;4;false;2;Hundre;C;ECB concertation tidspunkt 14:15 CET;2026-11-12;5,9691\n" +
        "B;Virkedag;JPY;Japanske yen;NOK;Norske kroner;SP;Spot;4;false;2;Hundre;C;ECB concertation tidspunkt 14:15 CET;2026-11-13;5,9772\n" +
        "B;Virkedag;JPY;Japanske yen;NOK;Norske kroner;SP;Spot;4;false;2;Hundre;C;ECB concertation tidspunkt 14:15 CET;2026-11-16;6,0155\n"
    private val euro = "$header\n" +
        "B;Virkedag;EUR;Euro;NOK;Norske kroner;SP;Spot;4;false;0;Enheter;C;ECB concertation tidspunkt 14:15 CET;2026-11-13;10,5695\n"

    @Test
    fun readsRatePerUnitFromNorgesBank() {
        val rate = ExchangeRates.parseNorgesBank(euro, "EUR", LocalDate.of(2026, 11, 13))!!
        assertEquals(BigDecimal("10.5695"), rate.nokPerUnit)
        assertEquals(LocalDate.of(2026, 11, 13), rate.date)
        assertEquals("Norges Bank", rate.source)
    }

    @Test
    fun yenIsQuotedPerHundred() {
        val rate = ExchangeRates.parseNorgesBank(yen, "JPY", LocalDate.of(2026, 11, 13))!!
        assertEquals(0, BigDecimal("0.059772").compareTo(rate.nokPerUnit))
    }

    @Test
    fun weekendUsesTheLastRateBefore() {
        // 14. november 2026 er en lørdag.
        val rate = ExchangeRates.parseNorgesBank(yen, "JPY", LocalDate.of(2026, 11, 14))!!
        assertEquals(LocalDate.of(2026, 11, 13), rate.date)
    }

    @Test
    fun noRateWhenTheAnswerIsNotRates() {
        assertNull(ExchangeRates.parseNorgesBank("", "EUR", LocalDate.of(2026, 11, 13)))
        assertNull(ExchangeRates.parseNorgesBank("<html>Not found</html>", "EUR", LocalDate.of(2026, 11, 13)))
        assertNull(ExchangeRates.parseNorgesBank(yen, "JPY", LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun readsRateFromFrankfurter() {
        val rate = ExchangeRates.parseFrankfurter("""[{"date":"2026-11-13","base":"EGP","quote":"NOK","rate":0.17859}]""", "EGP")!!
        assertEquals(BigDecimal("0.17859"), rate.nokPerUnit)
        assertEquals(LocalDate.of(2026, 11, 13), rate.date)
        assertEquals("Frankfurter", rate.source)
    }

    @Test
    fun noRateFromFrankfurterForUnknownCurrency() {
        assertNull(ExchangeRates.parseFrankfurter("[]", "XXX"))
        assertNull(ExchangeRates.parseFrankfurter("""{"status":422,"message":"invalid currency: XXX"}""", "XXX"))
    }

    @Test
    fun convertsToWholeOre() {
        // 27,50 euro til kurs 10,5695 er 290,66125 kroner.
        assertEquals(29066L, ExchangeRates.toOre(2750, BigDecimal("10.5695")))
        // 3 480 yen til 5,9772 per hundre er 208,00656 kroner.
        assertEquals(20801L, ExchangeRates.toOre(348000, BigDecimal("0.059772")))
        // Halve øre rundes opp.
        assertEquals(3L, ExchangeRates.toOre(100, BigDecimal("0.025")))
    }

    @Test
    fun describesRatePerUnitOrPerHundred() {
        assertEquals("1 EUR = 10,5695 kr", ExchangeRates.describe("EUR", BigDecimal("10.5695")))
        assertEquals("100 JPY = 5,9772 kr", ExchangeRates.describe("JPY", BigDecimal("0.059772")))
        assertEquals("100 EGP = 17,8590 kr", ExchangeRates.describe("EGP", BigDecimal("0.17859")))
    }

    @Test
    fun effectiveRateFollowsTheAmountOnTheVoucher() {
        // Banken trakk 295,00 kroner for 27,50 euro.
        val rate = ExchangeRates.effectiveRate(29500, 2750)!!
        assertEquals("1 EUR = 10,7273 kr", ExchangeRates.describe("EUR", rate))
        assertNull(ExchangeRates.effectiveRate(0, 2750))
        assertNull(ExchangeRates.effectiveRate(29500, 0))
    }
}
