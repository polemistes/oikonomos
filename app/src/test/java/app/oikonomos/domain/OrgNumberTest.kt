package app.oikonomos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrgNumberTest {
    @Test
    fun acceptsNumbersWithCorrectCheckDigit() {
        assertTrue(OrgNumber.isValid("974760673"))
        assertTrue(OrgNumber.isValid("923609016"))
    }

    @Test
    fun rejectsWrongCheckDigitWrongLengthAndLetters() {
        assertFalse(OrgNumber.isValid("987654321"))
        assertFalse(OrgNumber.isValid("97476067"))
        assertFalse(OrgNumber.isValid("9747606730"))
        assertFalse(OrgNumber.isValid("97476067A"))
    }

    @Test
    fun findsNumberWrittenWithSpacesDotsAndCountryPrefix() {
        assertEquals("974760673", OrgNumber.findIn(listOf("Butikken AS", "Org.nr 974 760 673 MVA")))
        assertEquals("974760673", OrgNumber.findIn(listOf("Foretaksregisteret NO974760673MVA")))
        assertEquals("974760673", OrgNumber.findIn(listOf("Org nr: 974.760.673")))
    }

    @Test
    fun ignoresNumberWhereOcrMisreadADigit() {
        assertNull(OrgNumber.findIn(listOf("Org.nr 974 760 678 MVA")))
    }

    @Test
    fun prefersLabelledNumberOverAnotherValidNumberAbove() {
        val rows = listOf("Ref 923609016", "Org.nr 974 760 673")
        assertEquals("974760673", OrgNumber.findIn(rows))
    }

    @Test
    fun neverSuggestsTheUsersOwnNumber() {
        val rows = listOf("Leverandøren AS", "Org.nr 974 760 673 MVA", "Kunde: Mitt Foretak, org.nr 923 609 016")
        assertEquals("974760673", OrgNumber.findIn(rows, own = "923609016"))
        assertNull(OrgNumber.findIn(listOf("Kunde org.nr 923 609 016"), own = "923609016"))
    }

    @Test
    fun doesNotReadPartOfALongerNumber() {
        assertNull(OrgNumber.findIn(listOf("Org.nr 9747606731")))
        assertNull(OrgNumber.findIn(listOf("7038010974760673")))
    }
}
