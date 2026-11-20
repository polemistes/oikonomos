package app.oikonomos.ocr

import android.content.Context
import android.graphics.Bitmap
import app.oikonomos.domain.OcrLine
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Tekstgjenkjenning med Tesseract. Språkmodellene for norsk og engelsk ligger i appen under
 * assets/tessdata og kopieres til appens lagring første gang, fordi Tesseract leser dem som vanlige
 * filer. Modeller for andre språk lastes ned til samme mappe av [LanguageModels].
 */
class TesseractReader(private val context: Context, private val models: LanguageModels) {
    // Motoren er dyr å starte og tåler ikke samtidig bruk, så den gjenbrukes bak en lås.
    private val mutex = Mutex()
    private var engine: TessBaseAPI? = null
    private var engineLanguages = ""

    /**
     * Leser bildet med språkene i [languages], på Tesseracts form, som «nor+eng». Gir null når
     * motoren ikke lot seg starte, for eksempel fordi en språkmodell mangler.
     */
    suspend fun read(bitmap: Bitmap, languages: String = DEFAULT_LANGUAGES): List<OcrLine>? = mutex.withLock {
        val tesseract = engineFor(languages) ?: return null
        tesseract.setImage(bitmap)
        tesseract.getUTF8Text()
        val lines = mutableListOf<OcrLine>()
        val iterator = tesseract.resultIterator
        if (iterator != null) {
            iterator.begin()
            do {
                val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)?.trim().orEmpty()
                val box = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                if (text.isNotEmpty()) lines += OcrLine(text, box.left, box.top, box.bottom)
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
            iterator.delete()
        }
        tesseract.clear()
        lines
    }

    /** Motoren startes på nytt når språket byttes, for språkene velges idet den starter. */
    private fun engineFor(languages: String): TessBaseAPI? {
        engine?.let { running ->
            if (engineLanguages == languages) return running
            running.recycle()
            engine = null
        }
        installBundledModels(File(models.root, "tessdata"))
        val tesseract = TessBaseAPI()
        if (!tesseract.init(models.root.absolutePath, languages)) {
            tesseract.recycle()
            return null
        }
        // En kvittering er én tekstblokk der etikett og beløp står på samme linje.
        tesseract.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
        engine = tesseract
        engineLanguages = languages
        return tesseract
    }

    private fun installBundledModels(target: File) {
        target.mkdirs()
        for (name in context.assets.list("tessdata").orEmpty()) {
            val file = File(target, name)
            if (file.exists()) continue
            // Skrives via en midlertidig fil, så en avbrutt kopiering ikke etterlater en halv modell.
            val partial = File(target, "$name.part")
            context.assets.open("tessdata/$name").use { input -> partial.outputStream().use { input.copyTo(it) } }
            partial.renameTo(file)
        }
    }

    companion object {
        const val DEFAULT_LANGUAGES = "nor+eng"

        /** Kvitteringer på andre språk har nesten alltid noe med latinske bokstaver også, så engelsk leses med. */
        fun languagesFor(model: TesseractModel): String = "${model.code}+eng"
    }
}
