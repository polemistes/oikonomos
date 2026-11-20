package app.oikonomos.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class StoredFile(val fileName: String, val mimeType: String)

/** Vedleggsfilene ligger i appens private lagring, og databasen viser til dem med filnavn. */
class AttachmentStore(private val context: Context) {
    val dir: File get() = File(context.filesDir, "attachments").apply { mkdirs() }

    fun file(fileName: String): File = File(dir, fileName)

    private val authority = "${context.packageName}.files"

    fun uriFor(fileName: String): Uri = FileProvider.getUriForFile(context, authority, file(fileName))

    /** Kopierer et bilde eller en PDF inn i lageret. Gir null for andre filtyper og for filer som ikke kan leses. */
    suspend fun import(uri: Uri): StoredFile? = withContext(Dispatchers.IO) {
        val mimeType = mimeTypeOf(uri) ?: return@withContext null
        val extension = when (mimeType) {
            "application/pdf" -> "pdf"
            "image/jpeg" -> "jpg"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "img"
        }
        val target = file("${UUID.randomUUID()}.$extension")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
            // Kamerabilder mellomlagres hos appen selv og trengs ikke etter at de er kopiert inn.
            if (uri.authority == authority) context.contentResolver.delete(uri, null, null)
            StoredFile(target.name, mimeType)
        } catch (_: Exception) {
            target.delete()
            null
        }
    }

    /** En tom midlertidig fil som kameraappen kan lagre et bilde i. */
    fun newCameraTarget(): Uri {
        val folder = File(context.cacheDir, "camera").apply { mkdirs() }
        return FileProvider.getUriForFile(context, authority, File(folder, "${UUID.randomUUID()}.jpg"))
    }

    /** Kopierer en fil til en delingsmappe under et lesbart navn, slik at mottakeren ser «Faktura-12.pdf». */
    suspend fun shareCopy(source: File, displayName: String): Uri = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "share").apply { mkdirs() }
        folder.listFiles().orEmpty().forEach { it.delete() }
        val copy = source.copyTo(File(folder, displayName), overwrite = true)
        FileProvider.getUriForFile(context, authority, copy)
    }

    fun delete(fileName: String) {
        file(fileName).delete()
    }

    /**
     * Fjerner filer som ingen bilag viser til, for eksempel etter at appen ble avsluttet midt i en
     * registrering. Ferske filer får stå, fordi de kan høre til et bilag som ikke er lagret ennå.
     */
    suspend fun deleteOrphans(referenced: Set<String>) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        dir.listFiles().orEmpty()
            .filter { it.name !in referenced && it.lastModified() < cutoff }
            .forEach { it.delete() }
    }

    private fun mimeTypeOf(uri: Uri): String? {
        val type = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }
        return type?.takeIf { it == "application/pdf" || it.startsWith("image/") }
    }
}
