package app.oikonomos.data

import android.net.Uri
import app.oikonomos.domain.ReceiptParser
import app.oikonomos.ocr.TextReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ImportProgress(val done: Int, val total: Int)

data class ImportResult(val imported: Int, val failed: Int)

/**
 * Legger inn mange filer på én gang, for eksempel en måneds fakturaer lastet ned fra nettbanken.
 * Hver fil blir en uavklart kvittering med forslag til leverandør, dato og beløp, som brukeren
 * ser over og fører i regnskapet senere.
 */
class ReceiptImporter(
    private val store: AttachmentStore,
    private val textReader: TextReader,
    private val repository: VoucherRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val current = MutableStateFlow<ImportProgress?>(null)
    private val finished = MutableSharedFlow<ImportResult>(extraBufferCapacity = 1)

    /** Null når ingen import pågår. */
    val progress: StateFlow<ImportProgress?> = current
    val results: SharedFlow<ImportResult> = finished

    fun start(uris: List<Uri>) {
        if (uris.isEmpty() || current.value != null) return
        current.value = ImportProgress(0, uris.size)
        scope.launch {
            var imported = 0
            try {
                uris.forEachIndexed { index, uri ->
                    if (import(uri)) imported++
                    current.value = ImportProgress(index + 1, uris.size)
                }
            } finally {
                current.value = null
                finished.tryEmit(ImportResult(imported, uris.size - imported))
            }
        }
    }

    /** Gir false når filen ikke er et bilde eller en PDF, eller ikke kunne leses. */
    private suspend fun import(uri: Uri): Boolean {
        val file = store.import(uri) ?: return false
        return try {
            val suggestion = textReader.read(store.file(file.fileName), file.mimeType)?.let { ReceiptParser.parse(it, LocalDate.now()) }
            repository.save(
                VoucherDraft(
                    id = 0,
                    type = VoucherType.EXPENSE,
                    date = suggestion?.date ?: LocalDate.now(),
                    amountOre = suggestion?.amountOre ?: 0,
                    vatRate = 0,
                    vatOre = 0,
                    counterparty = suggestion?.vendor.orEmpty(),
                    description = "",
                    categoryId = null,
                    attachments = listOf(file),
                ),
                book = false,
            )
            true
        } catch (e: Exception) {
            store.delete(file.fileName)
            false
        }
    }
}
