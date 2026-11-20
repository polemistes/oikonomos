package app.oikonomos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanyRegisterTest {
    @Test
    fun readsMainUnit() {
        // Forkortet svar fra Enhetsregisteret.
        val json = """
            {"organisasjonsnummer":"974760673","navn":"REGISTERENHETEN I BRØNNØYSUND",
             "forretningsadresse":{"adresse":["Havnegata 48"],"postnummer":"8900","poststed":"BRØNNØYSUND","land":"Norge"},
             "postadresse":{"adresse":["Postboks 900"],"postnummer":"8910","poststed":"BRØNNØYSUND"},
             "registrertIMvaregisteret":false,"konkurs":false}
        """.trimIndent()
        val company = CompanyRegister.parse(json)!!
        assertEquals("974760673", company.orgNumber)
        assertEquals("REGISTERENHETEN I BRØNNØYSUND", company.name)
        assertEquals("Havnegata 48", company.address)
        assertEquals("8900", company.postalCode)
        assertEquals("BRØNNØYSUND", company.city)
        assertEquals(false, company.vatRegistered)
    }

    @Test
    fun readsSubUnitWithLocationAddressOverSeveralLines() {
        val json = """
            {"organisasjonsnummer":"933489272","navn":"BUTIKKEN AVD SURNADAL",
             "beliggenhetsadresse":{"adresse":["c/o Senteret","Follerøvegen 20"],"postnummer":"6652","poststed":"SURNADAL"}}
        """.trimIndent()
        val company = CompanyRegister.parse(json)!!
        assertEquals("c/o Senteret\nFollerøvegen 20", company.address)
        assertEquals("SURNADAL", company.city)
    }

    @Test
    fun readsVatRegistrationAndToleratesMissingAddress() {
        val company = CompanyRegister.parse("""{"organisasjonsnummer":"923609016","navn":"EQUINOR ASA","registrertIMvaregisteret":true}""")!!
        assertEquals(true, company.vatRegistered)
        assertEquals("", company.address)
    }

    @Test
    fun givesNothingForAnErrorAnswer() {
        assertNull(CompanyRegister.parse("""{"status":404,"error":"Not Found"}"""))
        assertNull(CompanyRegister.parse("ikke json"))
        assertNull(CompanyRegister.parse(""))
    }
}
