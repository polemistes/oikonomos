package app.oikonomos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test
    fun parsesNorwegianAndPlainFormats() {
        assertEquals(123450L, Money.parse("1 234,50"))
        assertEquals(123450L, Money.parse("1.234,50"))
        assertEquals(123450L, Money.parse("1,234.50"))
        assertEquals(123450L, Money.parse("1234.5"))
        assertEquals(123400L, Money.parse("1.234"))
        assertEquals(19900L, Money.parse("kr 199,-"))
        assertEquals(19900L, Money.parse("199"))
        assertEquals(5L, Money.parse("0,05"))
        assertEquals(123450L, Money.parse("1 234,50 kr"))
    }

    @Test
    fun rejectsTextThatIsNotAnAmount() {
        assertNull(Money.parse(""))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("-50"))
        assertNull(Money.parse("1,2,3"))
        assertNull(Money.parse("12,345"))
    }

    @Test
    fun formatsWithGroupingAndTwoDecimals() {
        assertEquals("0,00", Money.format(0))
        assertEquals("0,05", Money.format(5))
        assertEquals("999,90", Money.format(99990))
        assertEquals("1 234,50", Money.format(123450))
        assertEquals("1 234 567,00", Money.format(123456700))
        assertEquals("-12,00", Money.format(-1200))
        assertEquals("1234,50", Money.formatPlain(123450))
    }

    @Test
    fun formattedAmountsParseBackToTheSameValue() {
        for (ore in listOf(0L, 5L, 99990L, 123450L, 123456700L)) {
            assertEquals(ore, Money.parse(Money.format(ore)))
            assertEquals(ore, Money.parse(Money.formatPlain(ore)))
        }
    }

    @Test
    fun vatPartOfGrossAmount() {
        assertEquals(2000L, Money.vatPart(10000, 25))
        assertEquals(1304L, Money.vatPart(10000, 15))
        assertEquals(0L, Money.vatPart(10000, 0))
    }
}
