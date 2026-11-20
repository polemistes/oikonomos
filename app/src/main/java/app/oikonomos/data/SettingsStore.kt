package app.oikonomos.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val companyName: String = "",
    val orgNumber: String = "",
    val vatRegistered: Boolean = false,
    /** Gateadresse, eventuelt over flere linjer. */
    val address: String = "",
    val postalCode: String = "",
    val city: String = "",
    val email: String = "",
    val phone: String = "",
    val bankAccount: String = "",
    /** Antall dager fra fakturadato til forfall på nye fakturaer. */
    val paymentDays: Int = 14,
    /** Laveste nummer neste faktura kan få, for den som har utstedt fakturaer før appen ble tatt i bruk. */
    val invoiceNumberStart: Int = 1,
    /** Fast tekst nederst på alle fakturaer, for eksempel hjemmelen for at det ikke er beregnet MVA. */
    val invoiceNote: String = "",
    /** Aksjeselskaper skal oppgi «Foretaksregisteret» på fakturaen. */
    val limitedCompany: Boolean = false,
    /** Sant når spørsmålene ved første oppstart er besvart. */
    val setupDone: Boolean = false,
)

/**
 * Tilstanden for sikkerhetskopiering på denne telefonen. Den er ikke med i sikkerhetskopien, så en
 * gjenoppretting endrer verken mappen eller tidspunktene.
 */
data class BackupState(
    /** Tidspunkt for siste zip-kopi i millisekunder, 0 når ingen er tatt. */
    val lastZipAt: Long = 0,
    /** Mappen for automatisk sikkerhetskopi, tom når den er slått av. */
    val folderUri: String = "",
    val folderName: String = "",
    val lastSyncAt: Long = 0,
    /** Sant når noe er endret siden siste kopi til mappen. */
    val pending: Boolean = false,
    /** Sant når siste forsøk på å kopiere til mappen mislyktes. */
    val failed: Boolean = false,
) {
    val automatic: Boolean get() = folderUri.isNotEmpty()
}

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private val companyName = stringPreferencesKey("company_name")
    private val orgNumber = stringPreferencesKey("org_number")
    private val vatRegistered = booleanPreferencesKey("vat_registered")
    private val lastZipAt = longPreferencesKey("last_backup_at")
    private val backupFolderUri = stringPreferencesKey("backup_folder_uri")
    private val backupFolderName = stringPreferencesKey("backup_folder_name")
    private val lastSyncAt = longPreferencesKey("backup_last_sync_at")
    private val backupPending = booleanPreferencesKey("backup_pending")
    private val backupFailed = booleanPreferencesKey("backup_failed")
    private val address = stringPreferencesKey("address")
    private val postalCode = stringPreferencesKey("postal_code")
    private val city = stringPreferencesKey("city")
    private val email = stringPreferencesKey("email")
    private val phone = stringPreferencesKey("phone")
    private val bankAccount = stringPreferencesKey("bank_account")
    private val paymentDays = intPreferencesKey("payment_days")
    private val invoiceNumberStart = intPreferencesKey("invoice_number_start")
    private val invoiceNote = stringPreferencesKey("invoice_note")
    private val limitedCompany = booleanPreferencesKey("limited_company")
    private val setupDone = booleanPreferencesKey("setup_done")

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    val backupState: Flow<BackupState> = context.dataStore.data.map { it.toBackupState() }

    suspend fun updateBackupState(transform: (BackupState) -> BackupState) {
        context.dataStore.edit { prefs ->
            val updated = transform(prefs.toBackupState())
            prefs[lastZipAt] = updated.lastZipAt
            prefs[backupFolderUri] = updated.folderUri
            prefs[backupFolderName] = updated.folderName
            prefs[lastSyncAt] = updated.lastSyncAt
            prefs[backupPending] = updated.pending
            prefs[backupFailed] = updated.failed
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs[companyName] = updated.companyName
            prefs[orgNumber] = updated.orgNumber
            prefs[vatRegistered] = updated.vatRegistered
            // Innstillingene er med i sikkerhetskopien, så en endring må kopieres.
            prefs[backupPending] = true
            prefs[address] = updated.address
            prefs[postalCode] = updated.postalCode
            prefs[city] = updated.city
            prefs[email] = updated.email
            prefs[phone] = updated.phone
            prefs[bankAccount] = updated.bankAccount
            prefs[paymentDays] = updated.paymentDays
            prefs[invoiceNumberStart] = updated.invoiceNumberStart
            prefs[invoiceNote] = updated.invoiceNote
            prefs[limitedCompany] = updated.limitedCompany
            prefs[setupDone] = updated.setupDone
        }
    }

    private fun Preferences.toBackupState() = BackupState(
        lastZipAt = this[lastZipAt] ?: 0,
        folderUri = this[backupFolderUri].orEmpty(),
        folderName = this[backupFolderName].orEmpty(),
        lastSyncAt = this[lastSyncAt] ?: 0,
        pending = this[backupPending] ?: false,
        failed = this[backupFailed] ?: false,
    )

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            companyName = this[companyName].orEmpty(),
            orgNumber = this[orgNumber].orEmpty(),
            vatRegistered = this[vatRegistered] ?: false,
            address = this[address].orEmpty(),
            postalCode = this[postalCode].orEmpty(),
            city = this[city].orEmpty(),
            email = this[email].orEmpty(),
            phone = this[phone].orEmpty(),
            bankAccount = this[bankAccount].orEmpty(),
            paymentDays = this[paymentDays] ?: defaults.paymentDays,
            invoiceNumberStart = this[invoiceNumberStart] ?: defaults.invoiceNumberStart,
            invoiceNote = this[invoiceNote].orEmpty(),
            limitedCompany = this[limitedCompany] ?: false,
            setupDone = this[setupDone] ?: false,
        )
    }
}
