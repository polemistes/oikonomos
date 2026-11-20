package app.oikonomos.ui

import android.icu.util.Currency
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.oikonomos.R
import java.util.Date
import java.util.Locale

/** Valutaene de fleste trenger, vist først. Resten står alfabetisk under. */
private val commonCurrencies = listOf("EUR", "USD", "GBP", "SEK", "DKK")

// Koder som begynner på X er gull, sølv og regneenheter, bortsett fra noen fellesvalutaer.
private val sharedCurrencies = setOf("XAF", "XOF", "XPF", "XCD")
private val norwegian = Locale("nb", "NO")

/** Valutaens navn på norsk, som «Japanske yen». Ukjente koder vises som de er. */
fun currencyName(code: String): String =
    try {
        Currency.getInstance(code).getDisplayName(norwegian).replaceFirstChar { it.titlecase(norwegian) }
    } catch (_: IllegalArgumentException) {
        code
    }

/** Valutaene som er i bruk i dag, som kode og norsk navn, sortert etter navn. */
private fun currentCurrencies(): List<Pair<String, String>> {
    val now = Date()
    return Currency.getAvailableCurrencies()
        .map { it.currencyCode }
        .filter { code ->
            code != "NOK" && (code[0] != 'X' || code in sharedCurrencies) && Currency.isAvailable(code, now, now)
        }
        .map { it to currencyName(it) }
        .sortedBy { it.second.lowercase(norwegian) }
}

/**
 * Velger valutaen på et bilag. [onPick] får ISO-koden, eller en tom tekst for norske kroner.
 * [recent] er valutaene brukeren sist har brukt, som vises øverst.
 */
@Composable
fun CurrencyPickerDialog(recent: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = remember { currentCurrencies() }
    val names = remember(all) { all.toMap() }
    val search = query.trim()
    val matches = if (search.isEmpty()) {
        val first = (recent + commonCurrencies).distinct().filter { it in names }
        first.map { it to names.getValue(it) } + all.filter { it.first !in first }
    } else {
        all.filter { (code, name) -> code.contains(search, ignoreCase = true) || name.contains(search, ignoreCase = true) }
    }
    // En kode som ikke står i listen, kan likevel brukes når søket ikke treffer noe. Kronebeløpet må da skrives inn.
    val typedCode = search.uppercase().takeIf { matches.isEmpty() && it.length == 3 && it.all(Char::isLetter) && it != "NOK" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.currency_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.currency_search)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
                val colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                val kroner = stringResource(R.string.currency_nok)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    if (search.isEmpty() || kroner.contains(search, ignoreCase = true) || "NOK".contains(search, ignoreCase = true)) {
                        item {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.currency_item, kroner, "NOK")) },
                                colors = colors,
                                modifier = Modifier.clickable { onPick("") },
                            )
                        }
                    }
                    items(matches, key = { it.first }) { (code, name) ->
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.currency_item, name, code)) },
                            colors = colors,
                            modifier = Modifier.clickable { onPick(code) },
                        )
                    }
                    if (typedCode != null) {
                        item {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.currency_use_code, typedCode)) },
                                colors = colors,
                                modifier = Modifier.clickable { onPick(typedCode) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
