package app.oikonomos

import android.app.Application
import android.content.Context
import android.net.Uri
import app.oikonomos.data.AppDatabase
import app.oikonomos.data.AttachmentStore
import app.oikonomos.data.AutoBackup
import app.oikonomos.data.BackupManager
import app.oikonomos.data.InvoiceRepository
import app.oikonomos.data.ReceiptImporter
import app.oikonomos.data.SettingsStore
import app.oikonomos.data.VoucherRepository
import app.oikonomos.data.YearExporter
import app.oikonomos.ocr.TextReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class OikonomosApp : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            container.repository.cleanUpOrphanFiles()
        }
    }
}

/** Appens felles objekter. Opprettes én gang og deles av skjermene. */
class AppContainer(context: Context) {
    val database = AppDatabase.create(context)
    val settings = SettingsStore(context)
    val attachments = AttachmentStore(context)
    val repository = VoucherRepository(database, attachments)
    val textReader = TextReader(context)
    val backup = BackupManager(context, database, attachments, settings)
    val autoBackup = AutoBackup(backup, settings)
    val importer = ReceiptImporter(attachments, textReader, repository)
    val invoices = InvoiceRepository(database, attachments, settings)
    val exporter = YearExporter(context, repository, database, database.invoiceDao(), attachments, settings)

    init {
        // Enhver endring i databasen merkes, slik at den automatiske sikkerhetskopien vet at den er utdatert.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            database.invalidationTracker
                .createFlow("voucher", "attachment", "category", "customer", "invoice", "invoice_line", "trip", "project", emitInitialState = false)
                .collect { settings.updateBackupState { it.copy(pending = true) } }
        }
    }

    /** Filer som er delt til appen fra en annen app, og som venter på å bli lagt i et nytt bilag. */
    val sharedInbox = MutableStateFlow<List<Uri>>(emptyList())
}
