package app.oikonomos.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import app.oikonomos.domain.BackupSnapshots
import app.oikonomos.domain.CsvExport
import app.oikonomos.domain.ExportRow
import app.oikonomos.domain.InvoiceCalc
import app.oikonomos.domain.InvoiceExportRow
import app.oikonomos.domain.InvoiceStatus
import app.oikonomos.domain.TripExportRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BackupData(
    val formatVersion: Int = 1,
    val settings: AppSettings,
    val categories: List<Category>,
    val vouchers: List<Voucher>,
    val attachments: List<Attachment>,
    val customers: List<Customer> = emptyList(),
    val invoices: List<Invoice> = emptyList(),
    val invoiceLines: List<InvoiceLine> = emptyList(),
    val trips: List<Trip> = emptyList(),
    val projects: List<Project> = emptyList(),
)

class BackupFormatException : IOException()

/**
 * Sikkerhetskopien inneholder alle data som JSON og vedleggene som filer. Den lagres der brukeren
 * velger, slik at bilagene overlever at telefonen blir borte. Den finnes i to former: en zip-fil
 * som lages på forespørsel, og en mappe som appen selv holder oppdatert.
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val store: AttachmentStore,
    private val settings: SettingsStore,
) {
    private val resolver get() = context.contentResolver
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private suspend fun snapshot() = BackupData(
        settings = settings.settings.first(),
        categories = db.categoryDao().all(),
        vouchers = db.voucherDao().allVouchers(),
        attachments = db.voucherDao().allAttachments(),
        customers = db.customerDao().all(),
        invoices = db.invoiceDao().allInvoices(),
        invoiceLines = db.invoiceDao().allLines(),
        trips = db.tripDao().all(),
        projects = db.projectDao().all(),
    )

    suspend fun write(target: Uri) = withContext(Dispatchers.IO) {
        val data = snapshot()
        val output = resolver.openOutputStream(target) ?: throw IOException("Kan ikke skrive til $target")
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(DATA_ENTRY))
            zip.write(json.encodeToString(BackupData.serializer(), data).toByteArray())
            zip.closeEntry()
            for (attachment in data.attachments) {
                val file = store.file(attachment.fileName)
                if (!file.exists()) continue
                zip.putNextEntry(ZipEntry(ATTACHMENT_PREFIX + attachment.fileName))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        settings.updateBackupState { it.copy(lastZipAt = System.currentTimeMillis()) }
    }

    /** Erstatter alt innhold i appen med innholdet i zip-filen. Eksisterende data røres ikke før kopien er lest og kontrollert. */
    suspend fun restore(source: Uri) = withContext(Dispatchers.IO) {
        withStaging { staging ->
            var data: BackupData? = null
            val input = resolver.openInputStream(source) ?: throw IOException("Kan ikke lese $source")
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    when {
                        name == DATA_ENTRY -> data = parse(zip.readBytes().decodeToString())
                        name.startsWith(ATTACHMENT_PREFIX) -> {
                            val fileName = name.removePrefix(ATTACHMENT_PREFIX)
                            if (isSafeFileName(fileName)) File(staging, fileName).outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
            commit(data ?: throw BackupFormatException(), staging)
        }
    }

    /**
     * Holder sikkerhetskopien i mappen oppdatert. Vedlegg endres aldri etter at de er lagret, så bare
     * nye filer kopieres. Dataene skrives som en ny liten fil hver gang, med tidspunktet i navnet, så
     * en eldre tilstand kan hentes tilbake. Gamle kopier ryddes bort etter reglene i [BackupSnapshots].
     */
    suspend fun syncToFolder(tree: Uri) = withContext(Dispatchers.IO) {
        val folder = BackupFolder(resolver, tree)
        val data = snapshot()
        val root = folder.children(folder.rootId)
        val attachmentsId = root.firstOrNull { it.isDirectory && it.name == ATTACHMENT_FOLDER }?.id
            ?: folder.createDirectory(folder.rootId, ATTACHMENT_FOLDER)
        val copied = folder.children(attachmentsId).associateBy { it.name }
        for (name in data.attachments.map { it.fileName }.distinct()) {
            val file = store.file(name)
            if (!file.exists()) continue
            val existing = copied[name]
            if (existing?.size == file.length()) continue
            // En fil med feil størrelse er rest etter en avbrutt kopiering.
            if (existing != null) folder.delete(existing.id)
            folder.write(attachmentsId, name, "application/octet-stream") { output -> file.inputStream().use { it.copyTo(output) } }
        }

        // Dataene skrives til slutt, så de aldri viser til vedlegg som ikke er kopiert ennå.
        val snapshotName = BackupSnapshots.name(LocalDateTime.now())
        root.filter { it.name == snapshotName }.forEach { folder.delete(it.id) }
        val text = json.encodeToString(BackupData.serializer(), data).toByteArray()
        folder.write(folder.rootId, snapshotName, "application/json") { it.write(text) }
        val obsolete = BackupSnapshots.toDelete(root.map { it.name } + snapshotName).toSet()
        root.filter { it.name in obsolete }.forEach { folder.delete(it.id) }
    }

    /** Tidspunktene det finnes kopier fra i mappen, nyeste først. */
    suspend fun snapshots(tree: Uri): List<LocalDateTime> = withContext(Dispatchers.IO) {
        val folder = BackupFolder(resolver, tree)
        folder.children(folder.rootId).mapNotNull { BackupSnapshots.time(it.name) }.sortedDescending()
    }

    /** Erstatter alt innhold i appen med kopien fra [time] i mappen. */
    suspend fun restoreFromFolder(tree: Uri, time: LocalDateTime) = withContext(Dispatchers.IO) {
        val folder = BackupFolder(resolver, tree)
        val root = folder.children(folder.rootId)
        val snapshot = root.firstOrNull { it.name == BackupSnapshots.name(time) } ?: throw BackupFormatException()
        val backup = parse(folder.read(snapshot.id) { it.readBytes().decodeToString() })
        val attachments = root.firstOrNull { it.isDirectory && it.name == ATTACHMENT_FOLDER }
            ?.let { folder.children(it.id) }.orEmpty().associateBy { it.name }
        withStaging { staging ->
            for (name in backup.attachments.map { it.fileName }.distinct()) {
                val entry = attachments[name] ?: continue
                if (isSafeFileName(name)) folder.read(entry.id) { input -> File(staging, name).outputStream().use { input.copyTo(it) } }
            }
            commit(backup, staging)
        }
    }

    /** Beholder tilgangen til mappen etter at appen er startet på nytt. */
    fun keepAccess(tree: Uri) {
        resolver.takePersistableUriPermission(tree, FOLDER_ACCESS)
    }

    fun releaseAccess(tree: String) {
        if (tree.isNotEmpty()) runCatching { resolver.releasePersistableUriPermission(Uri.parse(tree), FOLDER_ACCESS) }
    }

    /** Navnet på mappen, til visning i innstillingene. */
    suspend fun folderName(tree: Uri): String = withContext(Dispatchers.IO) {
        runCatching { BackupFolder(resolver, tree).rootName() }.getOrNull().orEmpty()
    }

    private suspend fun withStaging(block: suspend (File) -> Unit) {
        val staging = File(context.cacheDir, "restore").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            block(staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun commit(backup: BackupData, staging: File) {
        // Rekkefølgen følger fremmednøklene: bilag viser til fakturaer, kategorier, reiser og prosjekter,
        // reiser til prosjekter, og fakturaer til kunder.
        db.withTransaction {
            db.voucherDao().clearVouchers()
            db.invoiceDao().clear()
            db.customerDao().clear()
            db.categoryDao().clear()
            db.tripDao().clear()
            db.projectDao().clear()
            db.projectDao().insertAll(backup.projects)
            db.tripDao().insertAll(backup.trips)
            db.categoryDao().insertAll(backup.categories)
            db.customerDao().insertAll(backup.customers)
            db.invoiceDao().insertInvoices(backup.invoices)
            db.invoiceDao().insertLines(backup.invoiceLines)
            db.voucherDao().insertVouchers(backup.vouchers)
            db.voucherDao().insertAttachments(backup.attachments)
        }
        store.dir.listFiles().orEmpty().forEach { it.delete() }
        staging.listFiles().orEmpty().forEach { it.copyTo(store.file(it.name), overwrite = true) }
        settings.update { backup.settings }
    }

    /** Filnavn fra en sikkerhetskopi er ukjent innhold og får ikke peke ut av mappen. */
    private fun isSafeFileName(name: String): Boolean =
        name.isNotEmpty() && name.none { it == '/' || it == '\\' } && !name.startsWith(".")

    private fun parse(text: String): BackupData {
        val data = try {
            json.decodeFromString(BackupData.serializer(), text)
        } catch (_: Exception) {
            throw BackupFormatException()
        }
        if (data.formatVersion != 1) throw BackupFormatException()
        return data
    }

    private companion object {
        const val DATA_ENTRY = "oikonomos.json"
        const val ATTACHMENT_PREFIX = "attachments/"
        const val ATTACHMENT_FOLDER = "vedlegg"
        const val FOLDER_ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

/** En mappe brukeren har gitt appen tilgang til gjennom systemets filvelger. */
private class BackupFolder(private val resolver: ContentResolver, private val tree: Uri) {
    class Entry(val id: String, val name: String, val size: Long, val isDirectory: Boolean)

    val rootId: String = DocumentsContract.getTreeDocumentId(tree)

    private fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    fun rootName(): String =
        resolver.query(uri(rootId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()

    fun children(parentId: String): List<Entry> {
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val cursor = resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId), columns, null, null, null)
            ?: throw IOException("Kan ikke lese mappen")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(Entry(it.getString(0), it.getString(1).orEmpty(), it.getLong(2), it.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR))
                }
            }
        }
    }

    fun createDirectory(parentId: String, name: String): String {
        val created = DocumentsContract.createDocument(resolver, uri(parentId), DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Kan ikke opprette mappen $name")
        return DocumentsContract.getDocumentId(created)
    }

    /** Oppretter filen og fyller den. En halvskrevet fil fjernes igjen hvis skrivingen feiler. */
    fun write(parentId: String, name: String, mimeType: String, fill: (OutputStream) -> Unit) {
        val created = DocumentsContract.createDocument(resolver, uri(parentId), mimeType, name)
            ?: throw IOException("Kan ikke opprette filen $name")
        try {
            val output = resolver.openOutputStream(created) ?: throw IOException("Kan ikke skrive til $name")
            output.use(fill)
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw e
        }
    }

    fun <T> read(id: String, use: (InputStream) -> T): T {
        val input = resolver.openInputStream(uri(id)) ?: throw IOException("Kan ikke lese filen")
        return input.use(use)
    }

    fun delete(id: String) {
        DocumentsContract.deleteDocument(resolver, uri(id))
    }
}

/**
 * Eksport av ett regnskapsår: bilagsliste, sammendrag, fakturaliste og reiser som CSV, og vedleggene
 * navngitt etter bilagsnummer.
 */
class YearExporter(
    private val context: Context,
    private val repository: VoucherRepository,
    private val db: AppDatabase,
    private val invoiceDao: InvoiceDao,
    private val store: AttachmentStore,
    private val settings: SettingsStore,
) {
    /** Gir antall bilag som ble eksportert. */
    suspend fun export(year: Int, target: Uri): Int = withContext(Dispatchers.IO) {
        val vouchers = repository.listBooked(year)
        val files = mutableListOf<Pair<String, File>>()
        val rows = vouchers.map { item ->
            val names = item.sortedAttachments.mapIndexed { index, attachment ->
                val extension = attachment.fileName.substringAfterLast('.', "")
                val name = "${item.voucher.label}-${index + 1}.$extension"
                files += name to store.file(attachment.fileName)
                name
            }
            ExportRow(
                label = item.voucher.label,
                date = item.voucher.date,
                type = item.voucher.type,
                counterparty = item.voucher.counterparty,
                description = item.voucher.description,
                category = item.category?.name.orEmpty(),
                account = item.category?.account.orEmpty(),
                amountOre = item.voucher.amountOre,
                vatRate = item.voucher.vatRate,
                vatOre = item.voucher.vatOre,
                attachmentNames = names,
                trip = item.trip?.name.orEmpty(),
                project = item.project?.name.orEmpty(),
                cancelled = item.voucher.isCancelled,
                cancelReason = item.voucher.cancelReason,
                orgNumber = item.voucher.counterpartyOrgNumber,
                currency = item.voucher.currency,
                foreignAmount = item.voucher.foreignAmount,
                exchangeRateSource = item.voucher.exchangeRateSource,
            )
        }
        val vatRegistered = settings.settings.first().vatRegistered
        val today = LocalDate.now()
        val invoices = invoiceDao.issuedBetween(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)).map { item ->
            InvoiceExportRow(
                number = item.invoice.number ?: 0,
                creditNote = item.invoice.isCreditNote,
                issueDate = item.invoice.issueDate,
                dueDate = item.invoice.dueDate,
                customer = item.customerName,
                totalOre = item.invoice.totalOre,
                vatOre = item.invoice.vatOre,
                paidDate = item.invoice.paidDate,
                status = when (InvoiceCalc.status(item.invoice, item.credited, today)) {
                    InvoiceStatus.DRAFT -> "Kladd"
                    InvoiceStatus.UNPAID -> "Ubetalt"
                    InvoiceStatus.OVERDUE -> "Forfalt"
                    InvoiceStatus.PAID -> "Betalt"
                    InvoiceStatus.CREDITED -> "Kreditert"
                    InvoiceStatus.CREDIT_NOTE -> "Kreditnota"
                },
            )
        }
        // Reisene som helt eller delvis fant sted i året, med alle bilagene sine, også de som ble betalt året før.
        val projects = db.projectDao().all().associateBy { it.id }
        val onTrips = db.voucherDao().allOnTrips().filter { it.counts }.groupBy { it.tripId }
        val trips = db.tripDao().all()
            .filter { it.fromDate.year == year || it.toDate.year == year }
            .sortedBy { it.fromDate }
            .map { trip ->
                val own = onTrips[trip.id].orEmpty()
                TripExportRow(
                    name = trip.name,
                    fromDate = trip.fromDate,
                    toDate = trip.toDate,
                    project = projects[trip.projectId]?.name.orEmpty(),
                    note = trip.note,
                    voucherCount = own.size,
                    costOre = own.filter { it.type == VoucherType.EXPENSE }.sumOf { it.netOre },
                )
            }
        val output = context.contentResolver.openOutputStream(target) ?: throw IOException("Kan ikke skrive til $target")
        ZipOutputStream(output.buffered()).use { zip ->
            if (trips.isNotEmpty()) {
                zip.putNextEntry(ZipEntry("reiser-$year.csv"))
                zip.write((CsvExport.BOM + CsvExport.trips(trips)).toByteArray())
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("bilag-$year.csv"))
            zip.write((CsvExport.BOM + CsvExport.vouchers(rows, vatRegistered)).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("sammendrag-$year.csv"))
            zip.write((CsvExport.BOM + CsvExport.summary(rows)).toByteArray())
            zip.closeEntry()
            if (invoices.isNotEmpty()) {
                zip.putNextEntry(ZipEntry("fakturaer-$year.csv"))
                zip.write((CsvExport.BOM + CsvExport.invoices(invoices)).toByteArray())
                zip.closeEntry()
            }
            for ((name, file) in files) {
                if (!file.exists()) continue
                zip.putNextEntry(ZipEntry("vedlegg/$name"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        vouchers.size
    }
}
