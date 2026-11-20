package app.oikonomos

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.oikonomos.data.BackupWorker
import app.oikonomos.ocr.DocumentScanner
import app.oikonomos.ui.OikonomosNavHost
import app.oikonomos.ui.OikonomosTheme
import app.oikonomos.ui.SetupScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OikonomosApp).container
        // Bare ved første oppstart, så en skjermrotasjon ikke legger inn de samme filene på nytt.
        if (savedInstanceState == null) {
            // Skanneren hentes ved oppstart, så den er klar før første kvittering skal skannes.
            DocumentScanner.prepare(applicationContext)
            val shared = sharedFiles(intent)
            // Én delt fil åpner et nytt bilag. Flere filer er som regel flere kvitteringer, og importeres hver for seg.
            if (shared.size == 1) container.sharedInbox.value = shared else container.importer.start(shared)
        }
        setContent {
            OikonomosTheme {
                // Null til innstillingene er lest, så oppstartsspørsmålene ikke blinker forbi for den som alt har svart.
                val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
                val scope = rememberCoroutineScope()
                val current = settings
                when {
                    current == null -> Surface(Modifier.fillMaxSize()) {}
                    !current.setupDone -> SetupScreen(current) { answered ->
                        scope.launch { container.settings.update { answered } }
                    }
                    else -> OikonomosNavHost(container)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Når appen legges bort, kopieres endringer til sikkerhetskopimappen hvis en er valgt.
        if (!isChangingConfigurations) BackupWorker.request(applicationContext)
    }

    private fun sharedFiles(intent: Intent): List<Uri> {
        val uris = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        // Andre apper får bare levere innhold gjennom en innholdsleverandør, ikke peke på filstier.
        return uris.filter { it.scheme == "content" }
    }
}
