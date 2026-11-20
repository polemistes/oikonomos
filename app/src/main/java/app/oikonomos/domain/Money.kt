package app.oikonomos.domain

/** Beløp regnes i hele øre, så summer aldri får avrundingsfeil. */
object Money {
    private val whitespace = Regex("[\\s\\u00A0\\u202F]")
    private val dotGroupedThousands = Regex("^\\d{1,3}(\\.\\d{3})+$")
    private val plainDecimal = Regex("^(\\d{1,12})(?:\\.(\\d{1,2}))?$")

    /** Tolker «1 234,50», «1.234,50», «1234.5» og «kr 199,-». Gir null når teksten ikke er et beløp. */
    fun parse(input: String): Long? {
        var s = input.replace(whitespace, "").lowercase()
        s = s.removePrefix("kr").removePrefix("nok")
            .removeSuffix("kr").removeSuffix("nok")
            .removeSuffix(",-").removeSuffix(".-")
        val lastComma = s.lastIndexOf(',')
        val lastDot = s.lastIndexOf('.')
        s = when {
            // Begge tegn: det siste er desimaltegnet, det andre skiller tusener.
            lastComma >= 0 && lastDot >= 0 ->
                if (lastComma > lastDot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            lastComma >= 0 -> if (s.count { it == ',' } == 1) s.replace(',', '.') else return null
            lastDot >= 0 -> if (dotGroupedThousands.matches(s)) s.replace(".", "") else s
            else -> s
        }
        val match = plainDecimal.matchEntire(s) ?: return null
        val whole = match.groupValues[1].toLong()
        val fraction = match.groupValues[2].padEnd(2, '0').toLong()
        return whole * 100 + fraction
    }

    /** «1 234,50» med hardt mellomrom som tusenskille. */
    fun format(ore: Long): String {
        val abs = Math.abs(ore)
        val whole = (abs / 100).toString().reversed().chunked(3).joinToString(" ").reversed()
        val sign = if (ore < 0) "-" else ""
        return "$sign$whole,${(abs % 100).toString().padStart(2, '0')}"
    }

    fun formatKr(ore: Long): String = "${format(ore)} kr"

    /** «1234,50» uten tusenskille, for skjemafelt og regneark. */
    fun formatPlain(ore: Long): String {
        val abs = Math.abs(ore)
        val sign = if (ore < 0) "-" else ""
        return "$sign${abs / 100},${(abs % 100).toString().padStart(2, '0')}"
    }

    /** MVA-delen av et beløp som er oppgitt inkludert MVA. */
    fun vatPart(grossOre: Long, ratePercent: Int): Long =
        if (ratePercent <= 0) 0 else Math.round(grossOre * ratePercent / (100.0 + ratePercent))
}
