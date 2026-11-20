package app.oikonomos.ui.settings

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.oikonomos.AppContainer
import app.oikonomos.R
import app.oikonomos.data.AppSettings
import app.oikonomos.data.BackupFormatException
import app.oikonomos.data.BackupState
import app.oikonomos.ui.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime

data class RestoreChoices(val folder: Uri, val times: List<LocalDateTime>)

data class SettingsUiState(
    val loaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    /** Sant mens eksport, sikkerhetskopi eller gjenoppretting pågår. */
    val busy: Boolean = false,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val store = container.settings

    var state by mutableStateOf(SettingsUiState())
        private set

    val years: StateFlow<List<Int>> = container.repository.observeYears()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val backupState: StateFlow<BackupState> = store.backupState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupState())

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    init {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        state = state.copy(loaded = true, settings = store.settings.first())
    }

    /** Skjermen viser endringen med en gang, og lagringen følger etter. */
    fun change(transform: (AppSettings) -> AppSettings) {
        state = state.copy(settings = transform(state.settings))
        viewModelScope.launch { store.update(transform) }
    }

    fun export(year: Int, target: Uri) = runTask(R.string.export_failed) {
        val count = container.exporter.export(year, target)
        UiMessage(R.string.export_done, listOf(count))
    }

    fun backup(target: Uri) = runTask(R.string.backup_failed) {
        container.backup.write(target)
        UiMessage(R.string.backup_done)
    }

    fun restore(source: Uri) = runTask(R.string.restore_failed) {
        try {
            container.backup.restore(source)
            reload()
            UiMessage(R.string.restore_done)
        } catch (_: BackupFormatException) {
            UiMessage(R.string.restore_invalid)
        }
    }

    /** Kopiene brukeren kan velge mellom etter å ha pekt på en mappe, nyeste først. Null når ingen mappe er valgt. */
    var restoreChoices by mutableStateOf<RestoreChoices?>(null)
        private set

    fun loadSnapshots(tree: Uri) = runTask(R.string.restore_failed) {
        val times = container.backup.snapshots(tree)
        if (times.isEmpty()) {
            UiMessage(R.string.restore_folder_invalid)
        } else {
            restoreChoices = RestoreChoices(tree, times)
            null
        }
    }

    fun dismissSnapshots() {
        restoreChoices = null
    }

    fun restoreFromFolder(tree: Uri, time: LocalDateTime) = runTask(R.string.restore_failed) {
        restoreChoices = null
        try {
            container.backup.restoreFromFolder(tree, time)
            reload()
            UiMessage(R.string.restore_done)
        } catch (_: BackupFormatException) {
            UiMessage(R.string.restore_folder_invalid)
        }
    }

    /** Slår på automatisk sikkerhetskopi til mappen og tar første kopi med en gang. */
    fun chooseBackupFolder(tree: Uri) = runTask(R.string.auto_backup_failed) {
        val previous = backupState.value.folderUri
        container.backup.keepAccess(tree)
        if (previous != tree.toString()) container.backup.releaseAccess(previous)
        val name = container.backup.folderName(tree)
        store.updateBackupState {
            it.copy(folderUri = tree.toString(), folderName = name, lastSyncAt = 0, pending = true, failed = false)
        }
        syncMessage()
    }

    fun syncNow() = runTask(R.string.auto_backup_failed) { syncMessage() }

    fun disableAutoBackup() {
        viewModelScope.launch {
            container.backup.releaseAccess(backupState.value.folderUri)
            store.updateBackupState { it.copy(folderUri = "", folderName = "", lastSyncAt = 0, failed = false) }
        }
    }

    private suspend fun syncMessage() =
        UiMessage(if (container.autoBackup.run(force = true)) R.string.auto_backup_done else R.string.auto_backup_failed)

    /** [task] gir meldingen som skal vises når den er ferdig, eller null for ingen melding. */
    private fun runTask(failure: Int, task: suspend () -> UiMessage?) {
        if (state.busy) return
        viewModelScope.launch {
            state = state.copy(busy = true)
            val message = try {
                task()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                UiMessage(failure)
            }
            state = state.copy(busy = false)
            if (message != null) messageChannel.send(message)
        }
    }
}
