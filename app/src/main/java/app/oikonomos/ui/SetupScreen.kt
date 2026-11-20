package app.oikonomos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.oikonomos.R
import app.oikonomos.data.AppSettings

/**
 * Spørsmålene som stilles første gang appen åpnes. Svarene avgjør hvilke felt appen viser, slik at
 * ingenting er forhåndsvalgt for én type virksomhet. Alt kan endres under Innstillinger.
 */
@Composable
fun SetupScreen(current: AppSettings, onDone: (AppSettings) -> Unit) {
    var name by rememberSaveable { mutableStateOf(current.companyName) }
    var orgNumber by rememberSaveable { mutableStateOf(current.orgNumber) }
    var limitedCompany by rememberSaveable { mutableStateOf(current.limitedCompany) }
    // MVA-spørsmålet har ikke noe forhåndsvalg, med mindre appen alt er satt opp fra før.
    val configured = current.companyName.isNotBlank() || current.vatRegistered
    var vatRegistered by rememberSaveable { mutableStateOf(if (configured) current.vatRegistered else null) }

    // Knappen ligger fast nederst og følger med over tastaturet, så veien videre alltid er synlig.
    val vat = vatRegistered
    Scaffold(
        bottomBar = {
            Surface {
                Button(
                    onClick = {
                        if (vat != null) {
                            onDone(
                                current.copy(
                                    companyName = name.trim(),
                                    orgNumber = orgNumber,
                                    limitedCompany = limitedCompany,
                                    vatRegistered = vat,
                                    setupDone = true,
                                ),
                            )
                        }
                    },
                    enabled = vat != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) { Text(stringResource(R.string.setup_done)) }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.setup_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Valgene kommer først. De må besvares, og de trenger ikke tastatur.
            Question(R.string.setup_company_form)
            Choice(
                options = listOf(false to R.string.setup_sole_proprietorship, true to R.string.limited_company),
                selected = limitedCompany,
                onSelect = { limitedCompany = it },
            )

            Question(R.string.setup_vat)
            Choice(
                options = listOf(true to R.string.yes, false to R.string.no),
                selected = vatRegistered,
                onSelect = { vatRegistered = it },
            )
            Text(
                stringResource(R.string.setup_vat_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.setup_company_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = orgNumber,
                onValueChange = { text -> orgNumber = text.filter(Char::isDigit).take(9) },
                label = { Text(stringResource(R.string.setup_org_number)) },
                singleLine = true,
                // «Ferdig» på tastaturet lukker det.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Question(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun <T> Choice(options: List<Pair<T, Int>>, selected: T?, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) { Text(stringResource(label)) }
        }
    }
}
