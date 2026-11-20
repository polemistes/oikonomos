package app.oikonomos.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.oikonomos.domain.OcrLine
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Leser teksten i et bilde eller på første side av en PDF, på enheten. */
class TextReader(private val context: Context) {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Gir null når teksten ikke kunne leses. */
    suspend fun read(file: File, mimeType: String): List<OcrLine>? = withContext(Dispatchers.Default) {
        try {
            val image = if (mimeType == "application/pdf") {
                InputImage.fromBitmap(renderFirstPdfPage(file, 1600) ?: return@withContext null, 0)
            } else {
                InputImage.fromFilePath(context, Uri.fromFile(file))
            }
            recognizer.process(image).await().textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                OcrLine(line.text, box.left, box.top, box.bottom)
            }
        } catch (_: Exception) {
            null
        }
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
