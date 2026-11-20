package app.oikonomos.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import app.oikonomos.data.SettingsStore
import app.oikonomos.domain.OcrLine
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Leser teksten i et bilde eller på første side av en PDF, på enheten. Det finnes to motorer:
 * Googles ML Kit og Tesseract, som er fri programvare. Språket som er valgt i innstillingene, avgjør
 * hvilken som brukes: Google for skriftene den støtter, Tesseract for resten.
 */
class TextReader(private val context: Context, private val settings: SettingsStore, private val models: LanguageModels) {
    // Hver skrift har sin egen modell, som lastes inn i minnet første gang den brukes.
    private val google = mutableMapOf<GoogleScript, TextRecognizer>()
    private val tesseract = TesseractReader(context, models)

    /** Gir null når teksten ikke kunne leses. */
    suspend fun read(file: File, mimeType: String): List<OcrLine>? = withContext(Dispatchers.Default) {
        try {
            val current = settings.settings.first()
            val language = ReceiptLanguage.byId(current.receiptLanguage)
            // Mangler modellen, for eksempel etter gjenoppretting på en ny telefon, leses kvitteringen som vanlig.
            val model = language.tesseract?.takeIf(models::isInstalled)
            when {
                model != null -> loadBitmap(file, mimeType)?.let { bitmap ->
                    tesseract.read(bitmap, TesseractReader.languagesFor(model))?.plus(numbersIn(bitmap))
                }
                language == ReceiptLanguage.DEFAULT && current.useTesseract -> loadBitmap(file, mimeType)?.let { tesseract.read(it) }
                else -> readWithGoogle(file, mimeType, language.google ?: GoogleScript.LATIN)
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun readWithGoogle(file: File, mimeType: String, script: GoogleScript): List<OcrLine>? {
        val image = if (mimeType == "application/pdf") {
            InputImage.fromBitmap(renderFirstPdfPage(file, PDF_WIDTH) ?: return null, 0)
        } else {
            InputImage.fromFilePath(context, Uri.fromFile(file))
        }
        return recognizer(script).process(image).await().textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            OcrLine(line.text, box.left, box.top, box.bottom)
        }
    }

    /**
     * Tallene i bildet, lest med Googles tekstgjenkjenning. Tesseract leser bokstavene i skrifter Google
     * ikke kan, men bommer ofte på tall som står inntil dem, særlig i arabisk og hebraisk, som skrives
     * fra høyre. Tallene legges derfor til fra den andre motoren, med plassering i samme bilde, så de
     * havner på riktig rad.
     */
    private suspend fun numbersIn(bitmap: Bitmap): List<OcrLine> =
        try {
            recognizer(GoogleScript.LATIN).process(InputImage.fromBitmap(bitmap, 0)).await().textBlocks
                .flatMap { it.lines }.flatMap { it.elements }
                .mapNotNull { word ->
                    val box = word.boundingBox
                    if (box != null && number.matches(word.text)) OcrLine(word.text, box.left, box.top, box.bottom) else null
                }
        } catch (_: Exception) {
            emptyList()
        }

    private fun recognizer(script: GoogleScript): TextRecognizer = synchronized(google) {
        google.getOrPut(script) {
            TextRecognition.getClient(
                when (script) {
                    GoogleScript.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
                    GoogleScript.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
                    GoogleScript.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
                    GoogleScript.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
                    GoogleScript.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
                },
            )
        }
    }

    /** Bildet som Tesseract skal lese, rettvendt og ikke større enn nødvendig. */
    private fun loadBitmap(file: File, mimeType: String): Bitmap? {
        if (mimeType == "application/pdf") return renderFirstPdfPage(file, PDF_WIDTH)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        // Måles på korteste side: en lang, smal kvittering har liten tekst og tåler ikke å bli forminsket.
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SHORT_SIDE) sample *= 2
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder snur bildet etter retningen kameraet lagret, noe BitmapFactory ikke gjør.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(sample)
            }
        } else {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    private companion object {
        const val PDF_WIDTH = 1600
        const val MAX_SHORT_SIDE = 1800

        /** Et ord som bare er et tall, et beløp eller en dato. */
        val number = Regex("(?=.*\\d)[\\d.,:/-]+")
    }
}

/** Tegner første side av en PDF på hvit bakgrunn. Gir null hvis filen ikke kan åpnes som PDF. */
fun renderFirstPdfPage(file: File, width: Int): Bitmap? =
    try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val height = (width.toLong() * page.height / page.width).toInt().coerceIn(1, width * 4)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    } catch (_: Exception) {
        null
    }

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
