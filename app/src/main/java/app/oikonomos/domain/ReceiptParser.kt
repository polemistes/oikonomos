package app.oikonomos.domain

import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate

/** En tekstlinje fra tekstgjenkjenningen, med plassering i bildet. */
data class OcrLine(val text: String, val left: Int, val top: Int, val bottom: Int)

data class ReceiptSuggestion(
    /** Den mest sannsynlige leverandøren, altså første kandidat. */
    val vendor: String?,
    val date: LocalDate?,
    /** Totalbeløpet i øre. Er [currency] satt, er beløpet i hundredeler av den valutaen. */
    val amountOre: Long?,
    /** Linjene fra toppen av kvitteringen som kan være firmanavnet, i rekkefølge, til brukeren å velge blant. */
    val vendorCandidates: List<String> = emptyList(),
    /** Selgers organisasjonsnummer, når det står på kvitteringen og kontrollsifferet stemmer. */
    val orgNumber: String? = null,
    /** Valutaen som ISO-kode, eller null når ingenting tyder på annet enn norske kroner. */
    val currency: String? = null,
) {
    val isEmpty get() = vendor == null && date == null && amountOre == null
}

/** Gjetter leverandør, dato og totalbeløp fra teksten på en kvittering. Resultatet er et forslag brukeren må se over. */
object ReceiptParser {
    // Bokstaver med aksent skrives ofte uten på kvitteringer, særlig i store bokstaver. Står først, fordi
    // nøkkelordene under gjøres om med det samme mønsteret.
    private val accents = Regex("[\\u0300-\\u036F\\u064B-\\u0655]")

    // Tekstgjenkjenningen setter ofte inn mellomrom etter skilletegn («14.11. 2026», «567, 50»), så det tillates.
    private val numericDate = Regex("(?<!\\d)(\\d{1,2})[./-] ?(\\d{1,2})[./-] ?(\\d{4}|\\d{2})(?!\\d)")

    // Året først, slik det skrives i ISO-format og i Øst-Asia: «2026-11-14» og «2026/11/14».
    private val isoDate = Regex("(?<!\\d)(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(?!\\d)")
    private val kanjiDate = Regex("(?<!\\d)(\\d{4}) ?年 ?(\\d{1,2}) ?月 ?(\\d{1,2}) ?日")

    // Japanske kvitteringer daterer ofte etter keiserens regjeringsår. Reiwa 1 var 2019.
    private val reiwaDate = Regex("令和 ?(\\d{1,2}) ?年 ?(\\d{1,2}) ?月 ?(\\d{1,2}) ?日")
    private val reiwaShortDate = Regex("(?<![A-Za-z\\d])R ?(\\d{1,2})\\.(\\d{1,2})\\.(\\d{1,2})(?!\\d)")
    private const val REIWA_OFFSET = 2018

    // Thailandske kvitteringer bruker buddhistisk tidsregning, som ligger 543 år foran.
    private const val BUDDHIST_OFFSET = 543

    // Datoer med månedsnavn på norsk eller engelsk: «26. aug. 2026», «26 August 2026» og «Aug. 26, 2026».
    private const val MONTH = "(jan(?:uar|uary)?|feb(?:ruar|ruary)?|mar(?:s|ch)?|apr(?:il)?|mai|may|jun[ie]?|jul[iy]?|aug(?:ust)?|" +
        "sep(?:t|tember)?|okt(?:ober)?|oct(?:ober)?|nov(?:ember)?|des(?:ember)?|dec(?:ember)?)(?![a-zæøå])\\.?"
    private val dayMonthYear = Regex("(?<!\\d)(\\d{1,2})\\.? ?$MONTH,? ?(\\d{4})(?!\\d)", RegexOption.IGNORE_CASE)
    private val monthDayYear = Regex("(?<![a-zæøå])$MONTH ?(\\d{1,2})(?:st|nd|rd|th)?,? ?(\\d{4})(?!\\d)", RegexOption.IGNORE_CASE)
    private val monthNumbers = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "mai" to 5, "may" to 5, "jun" to 6, "jul" to 7,
        "aug" to 8, "sep" to 9, "okt" to 10, "oct" to 10, "nov" to 11, "des" to 12, "dec" to 12,
    )
    private val amount = Regex("(?<![\\d.,])(\\d{1,3}(?:\\.\\d{3})+|\\d+)[.,] ?(\\d{2})(?!\\d)")
    private val amountSpaceGrouped = Regex("(?<![\\d.,])(\\d{1,3}(?:[ .]\\d{3})+|\\d+)[.,] ?(\\d{2})(?!\\d)")

    // Engelsk skrivemåte, med komma mellom tusener og punktum foran desimalene: «1,234.56».
    private val amountCommaGrouped = Regex("(?<![\\d.,])\\d{1,3}(?:,\\d{3})+\\.\\d{2}(?!\\d)")

    // Et beløp uten desimaler, eventuelt med tusenskille: «3,480» yen. Prosenter og desimaltall er ikke med.
    private const val WHOLE = "(\\d{1,3}(?:[,.]\\d{3})+|\\d+)(?![\\d%]|[.,]\\d| ?%)"
    private val wholeAmount = Regex("(?<![\\d.,])$WHOLE")
    private const val MAX_WHOLE_AMOUNT = 999_999_999L

    private val totalKeywords = listOf(
        // norsk, svensk og dansk
        "å betale", "a betale", "til betaling", "totalt", "total", "sum", "beløp", "belop", "att betala", "at betale", "i alt",
        // engelsk, tysk og nederlandsk
        "amount due", "balance due", "gesamt", "zu zahlen", "betrag", "te betalen",
        // fransk, spansk, portugisisk og italiensk
        "montant", "à payer", "importe", "a pagar", "importo", "da pagare",
        // polsk, tsjekkisk, ungarsk, finsk og tyrkisk
        "razem", "do zapłaty", "celkem", "összesen", "yhteensä", "toplam", "tutar",
        // gresk
        "σύνολο", "πληρωτέο", "ποσό",
        // russisk, ukrainsk og bulgarsk
        "итог", "всего", "к оплате", "разом", "до сплати", "общо", "сума",
        // japansk, kinesisk og koreansk
        "合計", "お会計", "総計", "ご請求", "合计", "总计", "總計", "应付", "應付", "实付", "합계", "총액", "결제금액", "받을금액",
        // arabisk, hebraisk, thai og hindi
        "اجمالي", "المجموع", "الصافي", "المطلوب", "סה\"כ", "סהכ", "לתשלום", "รวม", "ยอดสุทธิ", "कुल",
    ).map(::fold)

    /** Delsummer inneholder ofte ordet for sum, men er ikke totalen. */
    private val subtotalWords = listOf(
        "subtotal", "sub total", "sub-total", "delsum", "zwischensumme", "sous-total", "sous total", "μερικό",
        "小計", "小计", "소계", "الفرعي", "подытог", "проміжн",
    ).map(::fold)

    /** Det kunden la på disken og fikk igjen. Begge er beløp, men ingen av dem er det som ble betalt. */
    private val changeWords = listOf(
        "tendered", "change", "wechselgeld", "rückgeld", "rendu", "ρέστα", "お預", "預り", "お釣", "釣銭", "おつり",
        "找零", "거스름", "الباقي", "المدفوع", "сдача", "решта",
    ).map(::fold)

    /** Ord på linja der betalingen står. Den viser totalen også når selve sumlinja ikke lot seg lese. */
    private val paymentWords = listOf(
        "kort", "visa", "mastercard", "vipps", "kontant", "betalt",
        "card", "paid", "karte", "carte", "tarjeta", "κάρτα", "クレジット", "カード", "카드", "بطاقة", "карта", "картка",
    ).map(::fold)
    private val currency = Regex("\\bnok\\b|\\bkr\\b|,-", RegexOption.IGNORE_CASE)
    private val moneySigns = Regex("[€$£¥₺₹₩₽₴₪฿₫円]")

    // Rader som oppgir avgiften eller beløpet uten avgift. Ord som «taxi» og «private» skal ikke treffe.
    private val taxWords = Regex("\\b(vat|tax|mwst|ust|tva|iva|btw|kdv|gst)\\b|steuer|φπα|消費税|税額|内税|增值税|부가세|ضريب|ндс|пдв|ддс")
    private val exTaxWords = listOf("eks", "excl", "税抜", "外税")
    private val inclusiveWords = listOf("inkl", "incl", "ttc", "税込", "含税", "συμπ", "شامل", "вкл", "포함")

    private val headerWords = setOf(
        "kvittering", "salgskvittering", "kopi", "faktura", "kassalapp", "kontantnota", "bong",
        "receipt", "invoice", "quittung", "rechnung", "reçu", "facture", "ricevuta", "factura", "απόδειξη",
        "領収書", "領収証", "レシート", "فاتورة", "чек",
    ).map(::fold).toSet()

    // En pris med valutategn, som viser at varelinjene har begynt også der beløpene ikke har desimaler.
    private val price = Regex("[€$£¥₺₹₩₽₴₪฿₫] ?\\d|\\d ?[€円원₫]")

    /** Linjer som står øverst på kvitteringer, men som aldri er firmanavnet. */
    private val notAName = Regex(
        "org\\.? ?nr|orgnr|foretaksregisteret|\\btlf\\b|telefon|www\\.|@|https?:|\\btel\\b|\\bphone\\b|τηλ|α\\.?φ\\.?μ|電話|〒|هاتف",
        RegexOption.IGNORE_CASE,
    )
    private const val MAX_VENDOR_CANDIDATES = 6

    // Usynlige tegn som styrer skriveretningen, og som tekstgjenkjenningen setter inn rundt tall i arabisk og hebraisk.
    private val directionMarks = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")

    /** [ownOrgNumber] er brukerens eget organisasjonsnummer, som aldri foreslås som selgerens. */
    fun parse(lines: List<OcrLine>, today: LocalDate, ownOrgNumber: String = ""): ReceiptSuggestion {
        val rows = toRows(lines).map(::normalize)
        val vendors = vendorCandidates(rows)
        val foreign = CurrencyDetector.detect(rows)
        return ReceiptSuggestion(
            vendor = vendors.firstOrNull(),
            date = findDate(rows, today),
            amountOre = findTotal(rows, foreign),
            vendorCandidates = vendors,
            // Utenlandske registreringsnumre har også ni sifre, så det letes bare på norske kvitteringer.
            orgNumber = if (foreign == null) OrgNumber.findIn(rows, ownOrgNumber) else null,
            currency = foreign,
        )
    }

    /**
     * Kvitteringer er satt opp i kolonner, og tekstgjenkjenningen gir ofte «Sum» og «199,00» som
     * hver sin linje. Linjer som står på samme høyde slås derfor sammen til én rad.
     */
    internal fun toRows(lines: List<OcrLine>): List<String> {
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in lines.sortedBy { it.top + it.bottom }) {
            val center = (line.top + line.bottom) / 2
            val current = rows.lastOrNull()?.takeIf { center in it.first().top..it.first().bottom }
            if (current != null) current += line else rows += mutableListOf(line)
        }
        return rows.map { row -> row.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }
    }

    /**
     * Gjør om arabiske, persiske og brede østasiatiske sifre og skilletegn til de vanlige, så tall og
     * datoer kan leses med de samme mønstrene uansett skrift.
     */
    internal fun normalize(text: String): String = buildString(text.length) {
        for (char in text) {
            append(
                when (char) {
                    in '٠'..'٩' -> '0' + (char - '٠')
                    in '۰'..'۹' -> '0' + (char - '۰')
                    in '０'..'９' -> '0' + (char - '０')
                    '٫', '，' -> ','
                    '٬', '．' -> '.'
                    '：' -> ':'
                    '／' -> '/'
                    '￥' -> '¥'
                    else -> char
                },
            )
        }
    }.replace(directionMarks, "")

    /**
     * Små bokstaver uten aksenter, til sammenligning med nøkkelordene. Arabisk skrives med og uten
     * hamza og med to former for avsluttende ja, og de regnes som like.
     */
    private fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(accents, "").replace('ς', 'σ').replace('ى', 'ي')

    /**
     * Firmanavnet står nesten alltid i toppen av kvitteringen, før varelinjene. Kandidatene er derfor
     * tekstlinjene ovenfra og ned til første linje med et beløp, uten overskrifter som «Salgskvittering»,
     * datoer og linjer med organisasjonsnummer, telefon eller nettadresse. Linjer på formen «etikett: verdi»
     * og linjer med mange sifre, som adresser og løpenumre, er heller ikke navn.
     */
    private fun vendorCandidates(rows: List<String>): List<String> =
        rows.take(12)
            .takeWhile { amountsIn(it, amount).isEmpty() && !price.containsMatchIn(it) }
            .map { it.trim() }
            .filter { row ->
                val firstWord = fold(row.takeWhile { it.isLetter() })
                row.count { it.isLetter() } >= 3 &&
                    firstWord !in headerWords &&
                    ':' !in row &&
                    row.count { it.isDigit() } < 5 &&
                    !notAName.containsMatchIn(row) &&
                    !numericDate.containsMatchIn(row) && !isoDate.containsMatchIn(row) && !kanjiDate.containsMatchIn(row)
            }
            .map { it.take(60) }
            .distinct()
            .take(MAX_VENDOR_CANDIDATES)

    private fun findDate(rows: List<String>, today: LocalDate): LocalDate? {
        val earliest = today.minusYears(10)
        val latest = today.plusDays(1)
        // «R8.11.14» er bare en dato på en japansk kvittering.
        val japanese = rows.any { row -> row.any(CurrencyDetector::isKana) }
        fun reiwa(match: MatchResult) =
            dateOrNull(REIWA_OFFSET + match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
        for (row in rows) {
            val candidates = isoDate.findAll(row).map { m ->
                dateOrNull(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            } + kanjiDate.findAll(row).map { m ->
                dateOrNull(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            } + reiwaDate.findAll(row).map(::reiwa) +
                (if (japanese) reiwaShortDate.findAll(row).map(::reiwa) else emptySequence()) +
                numericDate.findAll(row).map { m ->
                    val written = m.groupValues[3]
                    val year = written.toInt().let { if (it < 100) 2000 + it else it }
                    val first = m.groupValues[1].toInt()
                    val second = m.groupValues[2].toInt()
                    // Amerikanske datoer har måneden først. Med tosifret år kan tallene like gjerne være et klokkeslett.
                    dateOrNull(year, second, first) ?: if (written.length == 4) dateOrNull(year, first, second) else null
                } + dayMonthYear.findAll(row).map { m ->
                    dateOrNull(m.groupValues[3].toInt(), monthNumber(m.groupValues[2]), m.groupValues[1].toInt())
                } + monthDayYear.findAll(row).map { m ->
                    dateOrNull(m.groupValues[3].toInt(), monthNumber(m.groupValues[1]), m.groupValues[2].toInt())
                }
            candidates.firstOrNull { it != null && it in earliest..latest }?.let { return it }
        }
        return null
    }

    private fun monthNumber(name: String): Int = monthNumbers[name.take(3).lowercase()] ?: 0

    private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? =
        try {
            // Et årstall rundt 2569 er buddhistisk tidsregning.
            LocalDate.of(if (year in 2400..2700) year - BUDDHIST_OFFSET else year, month, day)
        } catch (_: DateTimeException) {
            null
        }

    /**
     * Totalen letes opp i tre trinn, fra sikrest til minst sikkert. Uten trinnene i mellom vinner
     * feillest støy lett, for eksempel et referansenummer fra kortterminalen lest som et stort beløp.
     */
    private fun findTotal(rows: List<String>, foreign: String?): Long? {
        // Hver rad sammen med teksten i små bokstaver uten aksenter, som nøkkelordene sammenlignes med.
        val folded = rows.map { it to fold(it) }
        fun exVat(lower: String) = exTaxWords.any { it in lower } ||
            (("mva" in lower || "moms" in lower || taxWords.containsMatchIn(lower)) && inclusiveWords.none { it in lower })

        fun notPaid(lower: String) = exVat(lower) || changeWords.any { it in lower }

        // 1. Rader som sier at de er summen.
        val totalRows = folded.filter { (_, lower) ->
            totalKeywords.any { it in lower } && subtotalWords.none { it in lower } && !notPaid(lower)
        }.map { it.first }
        // 2. Betalingslinja, og beløp som står med valuta.
        val paymentRows = folded.filter { (row, lower) ->
            !notPaid(lower) && (
                paymentWords.any { it in lower } || currency.containsMatchIn(lower) || moneySigns.containsMatchIn(row) ||
                    (foreign != null && foreign.lowercase() in lower)
                )
        }.map { it.first }

        if (foreign in CurrencyDetector.wholeUnits) {
            // Uten desimaler ser et beløp ut som ethvert annet tall. Utenfor sumradene teller derfor bare
            // tall som står sammen med valutaen.
            val marked = Regex("(?:[¥₩₫]|\\b$foreign) ?$WHOLE|(?<![\\d.,])$WHOLE ?(?:円|원|₫|Ft\\b|kr\\b|$foreign\\b)")
            totalRows.flatMap { wholeAmountsIn(it, wholeAmount) }.maxOrNull()?.let { return it }
            paymentRows.flatMap { wholeAmountsIn(it, marked) }.maxOrNull()?.let { return it }
            return folded.filter { (_, lower) -> !notPaid(lower) && subtotalWords.none { it in lower } }
                .flatMap { wholeAmountsIn(it.first, marked) }.maxOrNull()
        }

        // I en totalrad er «1 199,00» ett beløp. Ellers kan «1» være et antall foran en pris.
        totalRows.flatMap { amountsIn(it, amountSpaceGrouped) }.maxOrNull()?.let { return it }
        // Utenlands skrives summen ofte uten desimaler: «Total 150 EGP».
        if (foreign != null) totalRows.flatMap { wholeAmountsIn(it, wholeAmount) }.maxOrNull()?.let { return it }
        paymentRows.flatMap { amountsIn(it, amount) }.maxOrNull()?.let { return it }

        // 3. Det største beløpet som finnes, bortsett fra det kunden la på disken og fikk igjen.
        return folded.filter { (_, lower) -> changeWords.none { it in lower } }.flatMap { amountsIn(it.first, amount) }.maxOrNull()
    }

    private fun amountsIn(row: String, pattern: Regex): List<Long> =
        (pattern.findAll(row) + amountCommaGrouped.findAll(row)).mapNotNull { Money.parse(it.value) }.filter { it > 0 }.toList()

    /** Beløp uten desimaler, regnet om til hundredeler slik andre beløp er. */
    private fun wholeAmountsIn(row: String, pattern: Regex): List<Long> =
        pattern.findAll(row).mapNotNull { match ->
            match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.filter(Char::isDigit)?.toLongOrNull()
                ?.takeIf { it in 1..MAX_WHOLE_AMOUNT }?.times(100)
        }.toList()
}
