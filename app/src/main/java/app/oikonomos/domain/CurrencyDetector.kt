package app.oikonomos.domain

/** Finner ut hvilken valuta en kvittering er i, ut fra valutakoder, tegn og skrift i teksten. */
object CurrencyDetector {
    /** Valutaer som i praksis skrives uten desimaler, så «1,200» er tolv hundre og ikke 1,20. */
    val wholeUnits = setOf(
        "JPY", "KRW", "VND", "IDR", "CLP", "ISK", "HUF", "PYG", "UGX", "RWF", "XAF", "XOF", "KMF", "DJF", "GNF", "VUV", "XPF",
    )

    // Koder som også er vanlige ord i store bokstaver, som TRY, MAD, ALL og TOP, er ikke med.
    private val codes = listOf(
        "EUR", "USD", "GBP", "SEK", "DKK", "CHF", "JPY", "CNY", "EGP", "PLN", "CZK", "HUF", "BGN", "ISK",
        "AUD", "CAD", "NZD", "THB", "INR", "KRW", "SGD", "HKD", "TWD", "ZAR", "AED", "SAR", "QAR", "ILS", "TND",
        "BRL", "MXN", "IDR", "MYR", "PHP", "VND", "UAH", "RSD", "RUB", "KES", "TZS", "LKR", "NPR",
    )
    private val code = Regex("(?<![A-Za-z])(${codes.joinToString("|")})(?![A-Za-z])")
    private val nok = Regex("(?<![A-Za-z])NOK(?![A-Za-z])")
    private val kroner = Regex("\\bkr\\b|,-", RegexOption.IGNORE_CASE)

    /**
     * Tegn og forkortelser som bare brukes om én valuta. Ord og tegn som også kan være noe annet, må stå
     * inntil et tall: «Euro» er også et butikknavn, og dollartegnet er en vanlig feillesing av S.
     */
    private val markers = listOf(
        Regex("€|\\d ?euros?\\b|\\beuros? ?\\d|\\d ?eur\\b|ευρώ|ευρω", RegexOption.IGNORE_CASE) to "EUR",
        Regex("ج\\.م|(?<!\\p{L})ج ?م(?!\\p{L})|جنيه|\\d ?L\\.?E\\b|\\bL\\.?E\\.? ?\\d") to "EGP",
        Regex("円|[¥￥]") to "JPY",
        Regex("₺|\\d ?TL\\b") to "TRY",
        Regex("₹") to "INR",
        Regex("₩|원\\b") to "KRW",
        Regex("₽|\\bруб") to "RUB",
        Regex("₴|\\bгрн") to "UAH",
        Regex("₪") to "ILS",
        Regex("฿|บาท") to "THB",
        Regex("₫") to "VND",
        Regex("\\d ?zł") to "PLN",
        Regex("\\d ?Kč") to "CZK",
        Regex("\\d ?Ft\\b") to "HUF",
        Regex("ريال") to "SAR",
        Regex("درهم") to "AED",
        Regex("£ ?\\d+[.,]\\d{2}") to "GBP",
        Regex("\\$ ?\\d+[.,]\\d{2}") to "USD",
    )

    /**
     * Valutaen som ISO-kode, eller null når ingenting tyder på annet enn norske kroner. Rekkefølgen
     * går fra sikrest til minst sikkert: en utskrevet kode, så valutategn, så skriften teksten er i.
     */
    fun detect(rows: List<String>): String? {
        val text = rows.joinToString("\n")
        if (nok.containsMatchIn(text)) return null
        code.findAll(text).groupingBy { it.value }.eachCount().maxByOrNull { it.value }?.let { return it.key }
        // «kr» uten valutakode leses som norske kroner, selv om svenske og danske kvitteringer skriver det samme.
        if (kroner.containsMatchIn(text)) return null
        markers.firstOrNull { it.first.containsMatchIn(text) }?.let { (_, currency) ->
            // Yen-tegnet brukes også for kinesiske yuan.
            return if (currency == "JPY" && ('元' in text || "人民币" in text) && '円' !in text && text.none(::isKana)) "CNY" else currency
        }
        return fromScript(text)
    }

    /**
     * Mange kvitteringer oppgir ikke valuta i det hele tatt. Skriften avgjør da, der den i praksis
     * bare brukes i land med én valuta: gresk skrift betyr euro, japansk yen. Arabisk og kyrillisk
     * skrift brukes i for mange land til å si noe.
     */
    private fun fromScript(text: String): String? {
        val counts = text.groupingBy { scriptCurrency(it) }.eachCount() - null
        return counts.filter { it.value >= MIN_SCRIPT_LETTERS }.maxByOrNull { it.value }?.key
    }

    private fun scriptCurrency(char: Char): String? = when {
        char in '\u0370'..'\u03FF' -> "EUR" // gresk
        isKana(char) -> "JPY"
        char in '\uAC00'..'\uD7AF' -> "KRW" // hangul
        char in '\u0E00'..'\u0E7F' -> "THB" // thai
        char in '\u0590'..'\u05FF' -> "ILS" // hebraisk
        char in '\u0900'..'\u097F' -> "INR" // devanagari
        else -> null
    }

    /** Hiragana og katakana, som bare brukes i japansk. */
    fun isKana(char: Char) = char in '\u3040'..'\u30FF'

    private const val MIN_SCRIPT_LETTERS = 6
}
