package app.oikonomos.ocr

import android.content.Context
import app.oikonomos.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Språkmodellene til Tesseract på denne telefonen. Norsk og engelsk følger med appen og kopieres ut
 * av [TesseractReader]. De andre lastes ned hit når brukeren velger språket.
 */
class LanguageModels(context: Context) {
    /** Mappen Tesseract startes i. Modellene ligger i undermappen «tessdata», slik Tesseract krever. */
    val root = File(context.filesDir, MODEL_FOLDER)
    private val folder = File(root, "tessdata")

    fun isInstalled(model: TesseractModel): Boolean = File(folder, model.fileName).let { it.isFile && it.length() == model.bytes }

    /** Sant når språket kan brukes nå, altså at det ikke trenger en modell som mangler. */
    fun isReady(language: ReceiptLanguage): Boolean = language.tesseract?.let(::isInstalled) ?: true

    /** Laster ned modellen. Gir false uten nett, og når filen ikke er den som er kontrollert. */
    suspend fun download(model: TesseractModel, onProgress: (Float) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        if (isInstalled(model)) return@withContext true
        folder.mkdirs()
        try {
            Http.download(model.url, File(folder, model.fileName), model.sha256) { done ->
                onProgress((done.toFloat() / model.bytes).coerceIn(0f, 1f))
            }
        } catch (_: IOException) {
            false
        }
    }

    private companion object {
        /** Mappenavnet endres når modellene som følger med appen byttes ut, så nye filer blir kopiert inn. */
        const val MODEL_FOLDER = "tesseract-1"
    }
}
