package app.oikonomos.ocr

import app.oikonomos.R

/** Skriftene Googles tekstgjenkjenning har en modell for. Alle leser også latinske bokstaver. */
enum class GoogleScript { LATIN, JAPANESE, CHINESE, KOREAN, DEVANAGARI }

/**
 * En språkmodell for Tesseract, hentet fra tessdata_fast, utgave 4.1.0. Størrelsen og SHA-256-summen
 * er låst her, så appen bare tar i bruk nøyaktig den filen som er kontrollert.
 */
data class TesseractModel(val code: String, val bytes: Long, val sha256: String) {
    val fileName: String get() = "$code.traineddata"
    val url: String get() = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/4.1.0/$fileName"
}

/**
 * Språkene kvitteringer kan leses som. Googles tekstgjenkjenning brukes for skriftene den støtter, og
 * følger med appen. For de andre brukes Tesseract, med en språkmodell som lastes ned første gang.
 * Språk med latinske bokstaver trenger ikke noe valg: standardvalget leser dem.
 */
enum class ReceiptLanguage(
    /** Lagres i innstillingene, og må derfor ikke endres. */
    val id: String,
    val label: Int,
    val google: GoogleScript? = null,
    val tesseract: TesseractModel? = null,
) {
    DEFAULT("", R.string.lang_default, google = GoogleScript.LATIN),
    ARABIC("ar", R.string.lang_arabic, tesseract = TesseractModel("ara", 1_432_056, "e3206d3dc87fd50c24a0fb9f01838615911d25168f4e64415244b67d2bb3e729")),
    BULGARIAN("bg", R.string.lang_bulgarian, tesseract = TesseractModel("bul", 1_675_212, "aebc9b0fcc8cfaf8a9f38a02bb7b85052bd850744696a2c11cf0081820e5b21e")),
    GREEK("el", R.string.lang_greek, tesseract = TesseractModel("ell", 1_419_514, "4fba8a0b461038d51f1c20d043d4f2ac38c4e778f1b90830847f7bd8fa3ba726")),
    HEBREW("he", R.string.lang_hebrew, tesseract = TesseractModel("heb", 961_404, "11f9e43ab227f786352a50f75c94c2e9906f1baba86d93276da19da7ce0904db")),
    HINDI("hi", R.string.lang_hindi, google = GoogleScript.DEVANAGARI),
    JAPANESE("ja", R.string.lang_japanese, google = GoogleScript.JAPANESE),
    CHINESE("zh", R.string.lang_chinese, google = GoogleScript.CHINESE),
    KOREAN("ko", R.string.lang_korean, google = GoogleScript.KOREAN),
    RUSSIAN("ru", R.string.lang_russian, tesseract = TesseractModel("rus", 3_861_738, "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225")),
    THAI("th", R.string.lang_thai, tesseract = TesseractModel("tha", 1_072_600, "294227cc2d1292b0acb28d61d4115c88252b96d466ca90b417cf4cf0c67bf07c")),
    UKRAINIAN("uk", R.string.lang_ukrainian, tesseract = TesseractModel("ukr", 3_825_102, "d59e53e2bded32f4445f124b4b00240fcac7e8044c003ab822ccb94f0b3db59b")),
    ;

    companion object {
        /** Ukjent id, for eksempel fra en nyere utgave av appen, gir standardvalget. */
        fun byId(id: String): ReceiptLanguage = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
