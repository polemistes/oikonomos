package app.oikonomos.ocr

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Hvor langt nedlastingen av skanneren er kommet. */
sealed interface ScannerStatus {
    data object Checking : ScannerStatus
    data object Ready : ScannerStatus

    /** [percent] er null til Google Play-tjenester oppgir hvor mye som er lastet ned. */
    data class Downloading(val percent: Int?) : ScannerStatus

    /** [code] er feilkoden fra Google Play-tjenester, til hjelp ved feilsøking. */
    data class Failed(val code: Int) : ScannerStatus
}

/**
 * Googles dokumentskanner, som retter opp og beskjærer kvitteringen. Den er en modul i Google
 * Play-tjenester som lastes ned ved behov, og finnes derfor ikke nødvendigvis på enheten ennå.
 */
object DocumentScanner {
    private val status = MutableStateFlow<ScannerStatus>(ScannerStatus.Checking)

    /** Vises i appen, så brukeren ser om skanneren er klar eller hvorfor kameraet brukes i stedet. */
    val state: StateFlow<ScannerStatus> = status

    fun client(): GmsDocumentScanner = GmsDocumentScanning.getClient(
        GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setGalleryImportAllowed(false)
            .setPageLimit(10)
            .build(),
    )

    /**
     * Sjekker om skanneren er på plass, og ber ellers om at den lastes ned nå, ikke når det passer
     * Google Play-tjenester. Kan kalles flere ganger. En nedlasting som alt pågår, startes ikke på nytt.
     */
    fun prepare(context: Context) {
        if (status.value is ScannerStatus.Downloading) return
        try {
            val modules = ModuleInstall.getClient(context.applicationContext)
            lateinit var listener: InstallStatusListener
            listener = InstallStatusListener { update ->
                when (update.installState) {
                    InstallState.STATE_COMPLETED -> {
                        status.value = ScannerStatus.Ready
                        modules.unregisterListener(listener)
                    }
                    InstallState.STATE_FAILED, InstallState.STATE_CANCELED -> {
                        status.value = ScannerStatus.Failed(update.errorCode)
                        modules.unregisterListener(listener)
                    }
                    else -> {
                        val progress = update.progressInfo
                        val percent = progress?.takeIf { it.totalBytesToDownload > 0 }
                            ?.let { (it.bytesDownloaded * 100 / it.totalBytesToDownload).toInt() }
                        status.value = ScannerStatus.Downloading(percent)
                    }
                }
            }
            val request = ModuleInstallRequest.newBuilder().addApi(client()).setListener(listener).build()
            modules.installModules(request)
                .addOnSuccessListener { response ->
                    if (response.areModulesAlreadyInstalled()) {
                        status.value = ScannerStatus.Ready
                    } else if (status.value !is ScannerStatus.Ready) {
                        status.value = ScannerStatus.Downloading(null)
                    }
                }
                .addOnFailureListener { error -> status.value = ScannerStatus.Failed((error as? ApiException)?.statusCode ?: UNKNOWN_ERROR) }
        } catch (_: Exception) {
            status.value = ScannerStatus.Failed(UNKNOWN_ERROR)
        }
    }

    private const val UNKNOWN_ERROR = -1
}
