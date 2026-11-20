package app.oikonomos.data

import app.oikonomos.domain.Company
import app.oikonomos.domain.CompanyRegister
import app.oikonomos.domain.ExchangeRate
import app.oikonomos.domain.ExchangeRates
import app.oikonomos.domain.OrgNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

sealed interface CompanyResult {
    data class Found(val company: Company) : CompanyResult

    /** Registeret svarte at nummeret ikke finnes. */
    data object NotFound : CompanyResult

    /** Uten nett, eller oppslag er slått av. Da vet vi ingenting om nummeret. */
    data object Unavailable : CompanyResult
}

/**
 * Det appen henter fra nettet av seg selv: firmanavn fra Enhetsregisteret og valutakurser. Alt er
 * åpne, offentlige data. Det eneste som sendes, er organisasjonsnummeret som slås opp, eller
 * valutakoden og datoen. Ingenting om brukeren, foretaket eller bilagene forlater telefonen.
 */
class OnlineLookups(private val settings: SettingsStore) {
    private val companies = ConcurrentHashMap<String, Company>()
    private val rates = ConcurrentHashMap<Pair<String, LocalDate>, ExchangeRate>()

    suspend fun enabled(): Boolean = settings.settings.first().onlineLookups

    /** Slår opp et organisasjonsnummer. Underenheter, som en enkelt butikk i en kjede, ligger i et eget register. */
    suspend fun company(orgNumber: String): CompanyResult {
        if (!OrgNumber.isValid(orgNumber) || !enabled()) return CompanyResult.Unavailable
        companies[orgNumber]?.let { return CompanyResult.Found(it) }
        return withContext(Dispatchers.IO) {
            try {
                // Bare et klart nei fra begge registrene betyr at nummeret ikke finnes. En feil hos tjenesten gjør det ikke.
                var failed = false
                val company = listOf("enheter", "underenheter").firstNotNullOfOrNull { register ->
                    val response = Http.get("$BRREG/$register/$orgNumber")
                    when (response.code) {
                        HttpURLConnection.HTTP_OK -> CompanyRegister.parse(response.body)
                        HttpURLConnection.HTTP_NOT_FOUND, HttpURLConnection.HTTP_GONE -> null
                        else -> null.also { failed = true }
                    }
                }
                when {
                    company != null -> CompanyResult.Found(company).also { companies[orgNumber] = company }
                    failed -> CompanyResult.Unavailable
                    else -> CompanyResult.NotFound
                }
            } catch (_: IOException) {
                CompanyResult.Unavailable
            }
        }
    }

    /**
     * Kursen mot kroner på [date], eller siste notering før. Norges Bank er kilden der banken noterer
     * valutaen. For de andre brukes Frankfurter, som samler kurser fra flere sentralbanker. Gir null
     * uten nett, når oppslag er slått av, og når ingen av kildene har valutaen.
     */
    suspend fun rate(currency: String, date: LocalDate): ExchangeRate? {
        if (!currencyCode.matches(currency) || !enabled()) return null
        rates[currency to date]?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                (norgesBank(currency, date) ?: frankfurter(currency, date))?.also { rates[currency to date] = it }
            } catch (_: IOException) {
                null
            }
        }
    }

    private fun norgesBank(currency: String, date: LocalDate): ExchangeRate? {
        // Banken noterer ikke i helger og på helligdager, så det spørres etter noen dager bakover.
        val from = date.minusDays(LOOK_BACK_DAYS)
        val response = Http.get("$NORGES_BANK/B.$currency.NOK.SP?format=csv&startPeriod=$from&endPeriod=$date&locale=no")
        return if (response.code == HttpURLConnection.HTTP_OK) ExchangeRates.parseNorgesBank(response.body, currency, date) else null
    }

    private fun frankfurter(currency: String, date: LocalDate): ExchangeRate? {
        val response = Http.get("$FRANKFURTER/rates?date=$date&base=$currency&quotes=NOK")
        return if (response.code == HttpURLConnection.HTTP_OK) ExchangeRates.parseFrankfurter(response.body, currency) else null
    }

    private companion object {
        const val BRREG = "https://data.brreg.no/enhetsregisteret/api"
        const val NORGES_BANK = "https://data.norges-bank.no/api/data/EXR"
        const val FRANKFURTER = "https://api.frankfurter.dev/v2"
        const val LOOK_BACK_DAYS = 10L
        val currencyCode = Regex("[A-Z]{3}")
    }
}

/** Enkle nedlastinger over HTTPS. Brukes bare av oppslagene over og av nedlastingen av språkmodeller. */
object Http {
    class Response(val code: Int, val body: String)

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_TEXT_BYTES = 1_000_000

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // Navnet på appen og ingenting annet, så tjenestene ser hva slags klient som spør.
            setRequestProperty("User-Agent", "Oikonomos")
            setRequestProperty("Accept", "application/json, text/csv, */*")
        }

    /** Henter en kort tekst. Kaster IOException når tjenesten ikke kan nås. */
    fun get(url: String): Response {
        val connection = open(url)
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val bytes = input.readNBytesCompat(MAX_TEXT_BYTES)
                String(bytes, Charsets.UTF_8)
            }.orEmpty()
            return Response(code, body)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Laster ned en fil til [target]. Filen skrives først under et midlertidig navn og får bare det
     * riktige navnet når SHA-256-summen er [sha256], så en avbrutt eller endret nedlasting aldri tas i bruk.
     */
    fun download(url: String, target: File, sha256: String, onProgress: (Long) -> Unit = {}): Boolean {
        val partial = File(target.parentFile, target.name + ".part")
        val connection = open(url)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        total += read
                        onProgress(total)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            return actual == sha256 && partial.renameTo(target)
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
