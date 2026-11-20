package app.oikonomos.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Et foretak slik det står i Enhetsregisteret. */
data class Company(
    val orgNumber: String,
    val name: String,
    /** Gateadressen, eventuelt over flere linjer. */
    val address: String = "",
    val postalCode: String = "",
    val city: String = "",
    val vatRegistered: Boolean = false,
)

/** Tolker svaret fra Enhetsregisterets åpne API. Selve oppslaget ligger i data-laget. */
object CompanyRegister {
    fun parse(json: String): Company? =
        try {
            val unit = Json.parseToJsonElement(json).jsonObject
            val number = unit.text("organisasjonsnummer")
            val name = unit.text("navn")
            if (number.isEmpty() || name.isEmpty()) {
                null
            } else {
                // Hovedenheter har forretningsadresse, underenheter beliggenhetsadresse.
                val address = listOf("forretningsadresse", "beliggenhetsadresse", "postadresse")
                    .firstNotNullOfOrNull { unit[it] as? JsonObject }
                Company(
                    orgNumber = number,
                    name = name,
                    address = address?.get("adresse")?.jsonArray?.joinToString("\n") { it.jsonPrimitive.content }.orEmpty(),
                    postalCode = address?.text("postnummer").orEmpty(),
                    city = address?.text("poststed").orEmpty(),
                    vatRegistered = unit["registrertIMvaregisteret"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }
        } catch (_: RuntimeException) {
            null
        }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty().trim()
}
