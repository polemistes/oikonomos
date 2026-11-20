package app.oikonomos.domain

/** Norske organisasjonsnumre: ni sifre der det siste er et kontrollsiffer. */
object OrgNumber {
    private val weights = intArrayOf(3, 2, 7, 6, 5, 4, 3, 2)

    // «987 654 321», «987.654.321» og «NO987654321MVA». Sifre rett foran eller bak betyr et lengre tall, som en strekkode.
    private val candidate = Regex("(?<!\\d)(\\d{3})[ .]?(\\d{3})[ .]?(\\d{3})(?!\\d)")
    private val label = Regex("org|foretaksreg|mva|\\bvat\\b", RegexOption.IGNORE_CASE)

    fun isValid(number: String): Boolean {
        if (number.length != 9 || number.any { it !in '0'..'9' }) return false
        val sum = weights.indices.sumOf { weights[it] * (number[it] - '0') }
        // Rest 1 ville gitt kontrollsiffer 10, og slike numre deles ikke ut.
        val check = (11 - sum % 11) % 11
        return check == number[8] - '0'
    }

    /**
     * Selgers organisasjonsnummer blant tekstradene på en kvittering, eller null. Bare numre der
     * kontrollsifferet stemmer, godtas, så et feillest siffer gir ikke et galt forslag. Rader som sier
     * at de inneholder et organisasjonsnummer, går foran andre. [own] er brukerens eget nummer, som
     * står på fakturaer brukeren har mottatt, og som aldri er selgerens.
     */
    fun findIn(rows: List<String>, own: String = ""): String? {
        fun numbers(row: String) = candidate.findAll(row)
            .map { it.groupValues[1] + it.groupValues[2] + it.groupValues[3] }
            .filter { isValid(it) && it != own }
        rows.filter { label.containsMatchIn(it) }.firstNotNullOfOrNull { numbers(it).firstOrNull() }?.let { return it }
        // Uten etikett godtas bare numre som begynner på 8 eller 9, slik alle organisasjonsnumre har gjort til nå.
        return rows.firstNotNullOfOrNull { row -> numbers(row).firstOrNull { it[0] == '8' || it[0] == '9' } }
    }
}
