package app.oikonomos.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** En valutakurs: kroner per enhet av valutaen, slik den ble notert en bestemt dag. */
data class ExchangeRate(val currency: String, val nokPerUnit: BigDecimal, val date: LocalDate, val source: String)

/** Omregning til kroner, og tolking av svarene fra kurskildene. Selve nedlastingen ligger i data-laget. */
object ExchangeRates {
    const val NORGES_BANK = "Norges Bank"
    const val FRANKFURTER = "Frankfurter"

    /** «Norges Bank 13.11.2026», slik kilden og kursdatoen lagres på bilaget. */
    fun sourceLabel(rate: ExchangeRate): String = "${rate.source} ${rate.date.format(dateFormat)}"

    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /** Kronebeløpet i øre for [foreignAmount], som er i hundredeler av valutaen. */
    fun toOre(foreignAmount: Long, nokPerUnit: BigDecimal): Long =
        BigDecimal(foreignAmount).multiply(nokPerUnit).setScale(0, RoundingMode.HALF_UP).toLong()

    /** Kursen kronebeløpet på bilaget faktisk svarer til, også når brukeren har skrevet inn det banken trakk. */
    fun effectiveRate(amountOre: Long, foreignAmount: Long): BigDecimal? =
        if (amountOre <= 0 || foreignAmount <= 0) null else BigDecimal(amountOre).divide(BigDecimal(foreignAmount), 8, RoundingMode.HALF_UP)

    /** «1 EUR = 10,5695 kr». Valutaer der en enhet er verdt under en krone, oppgis per 100, slik Norges Bank gjør. */
    fun describe(currency: String, nokPerUnit: BigDecimal): String {
        val per100 = nokPerUnit < BigDecimal.ONE
        val value = (if (per100) nokPerUnit.movePointRight(2) else nokPerUnit).setScale(4, RoundingMode.HALF_UP)
        return "${if (per100) 100 else 1} $currency = ${value.toPlainString().replace('.', ',')} kr"
    }

    /**
     * Norges Banks kurser som semikolondelt CSV. Kursen gjelder [UNIT_MULT] tierpotenser av valutaen,
     * så yen oppgis per 100. Banken noterer bare på virkedager, og svaret dekker derfor flere dager.
     * Den siste noteringen til og med [onOrBefore] brukes.
     */
    fun parseNorgesBank(csv: String, currency: String, onOrBefore: LocalDate): ExchangeRate? {
        val lines = csv.lines().filter { it.isNotBlank() }
        val header = lines.firstOrNull()?.split(';') ?: return null
        val unit = header.indexOf("UNIT_MULT")
        val day = header.indexOf("TIME_PERIOD")
        val value = header.indexOf("OBS_VALUE")
        if (unit < 0 || day < 0 || value < 0) return null
        return lines.drop(1).mapNotNull { line ->
            val cells = line.split(';')
            try {
                val date = LocalDate.parse(cells[day])
                val rate = BigDecimal(cells[value].replace(',', '.')).movePointLeft(cells[unit].toInt())
                ExchangeRate(currency, rate, date, NORGES_BANK).takeIf { rate.signum() > 0 && !date.isAfter(onOrBefore) }
            } catch (_: RuntimeException) {
                null
            }
        }.maxByOrNull { it.date }
    }

    /** Svaret fra Frankfurter: en liste med én kurs mot kroner. */
    fun parseFrankfurter(json: String, currency: String): ExchangeRate? =
        try {
            val entry = Json.parseToJsonElement(json).jsonArray.firstOrNull()?.jsonObject
            val rate = entry?.get("rate")?.jsonPrimitive?.content?.let(::BigDecimal)
            val date = entry?.get("date")?.jsonPrimitive?.content?.let(LocalDate::parse)
            if (rate != null && date != null && rate.signum() > 0) ExchangeRate(currency, rate, date, FRANKFURTER) else null
        } catch (_: RuntimeException) {
            null
        }
}
