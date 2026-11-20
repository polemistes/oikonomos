package app.oikonomos.data

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.oikonomos.OikonomosApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** Kopierer til mappen brukeren har valgt, når noe er endret siden sist. */
class AutoBackup(private val backup: BackupManager, private val settings: SettingsStore) {
    private val mutex = Mutex()

    /** Gir false når kopieringen mislyktes. Uten valgt mappe, eller uten endringer, er det ingenting å gjøre. */
    suspend fun run(force: Boolean = false): Boolean = mutex.withLock {
        val state = settings.backupState.first()
        if (!state.automatic || (!state.pending && !force)) return true
        // Flagget nullstilles før kopieringen, så en endring som kommer underveis gir en ny kopi etterpå.
        settings.updateBackupState { it.copy(pending = false) }
        try {
            backup.syncToFolder(Uri.parse(state.folderUri))
            settings.updateBackupState { it.copy(lastSyncAt = System.currentTimeMillis(), failed = false) }
            true
        } catch (e: CancellationException) {
            settings.updateBackupState { it.copy(pending = true) }
            throw e
        } catch (_: Exception) {
            settings.updateBackupState { it.copy(pending = true, failed = true) }
            false
        }
    }
}

/** Kjører kopieringen i bakgrunnen, også etter at appen er lukket. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as OikonomosApp).container
        return when {
            container.autoBackup.run() -> Result.success()
            runAttemptCount < MAX_RETRIES -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "auto-backup"
        private const val MAX_RETRIES = 2

        /** Ber om en kopiering. En jobb som alt venter eller kjører, får stå. */
        fun request(context: Context) {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
