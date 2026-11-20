package app.oikonomos.domain

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Navn og opprydding for kopiene av dataene i sikkerhetskopimappen. Hver kopi er en liten fil med
 * tidspunktet i navnet, slik at brukeren kan gå tilbake til et tidspunkt før noe gikk galt.
 */
object BackupSnapshots {
    const val KEEP_NEWEST = 10
    const val KEEP_DAYS = 30
    private const val PREFIX = "oikonomos-"
    private val pattern = Regex("oikonomos-\\d{4}-\\d{2}-\\d{2}-\\d{6}\\.json")
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

    fun name(time: LocalDateTime): String = "$PREFIX${time.format(format)}.json"

    /** Tidspunktet i et filnavn, eller null når filen ikke er en kopi fra appen. */
    fun time(name: String): LocalDateTime? {
        if (!pattern.matches(name)) return null
        return runCatching { LocalDateTime.parse(name.removePrefix(PREFIX).removeSuffix(".json"), format) }.getOrNull()
    }

    /**
     * Kopiene som kan fjernes: de [KEEP_NEWEST] nyeste beholdes alltid, og i tillegg den siste kopien
     * fra hver av de [KEEP_DAYS] siste dagene det finnes kopier fra.
     */
    fun toDelete(names: List<String>): List<String> {
        val newestFirst = names.filter { time(it) != null }.sortedDescending()
        val keep = newestFirst.take(KEEP_NEWEST).toMutableSet()
        newestFirst.groupBy { it.substring(PREFIX.length, PREFIX.length + 10) }
            .entries.take(KEEP_DAYS)
            .forEach { keep += it.value.first() }
        return newestFirst.filter { it !in keep }
    }
}
