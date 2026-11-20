package app.oikonomos.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import app.oikonomos.R
import app.oikonomos.domain.InvoiceStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Hovedskjermene som nås fra fanene nederst. */
enum class Tab(val label: Int, val icon: Int) {
    VOUCHERS(R.string.tab_vouchers, R.drawable.ic_tab_vouchers),
    TRIPS(R.string.tab_trips, R.drawable.ic_tab_trips),
    INVOICES(R.string.tab_invoices, R.drawable.ic_tab_invoices),
    OVERVIEW(R.string.tab_overview, R.drawable.ic_tab_overview),
}

@Composable
fun TabBar(current: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == current,
                onClick = { if (tab != current) onSelect(tab) },
                icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                label = { Text(stringResource(tab.label)) },
            )
        }
    }
}

/**
 * Skjema som fyller skjermen, med «Lagre» i topplinjen. Brukes for skjemaer med flere felt, der
 * knappene nederst i en vanlig dialogboks ville havnet bak tastaturet.
 */
@Composable
fun FormDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean = true,
    /** Viser en sletteknapp når den er satt. */
    onDelete: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        // Vinduet går kant i kant, slik at innholdet selv kan vike for tastaturet.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Dialogvinduet arver ikke appens valg av mørke eller lyse ikoner i statuslinjen.
        val view = LocalView.current
        val darkTheme = isSystemInDarkTheme()
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        if (onDelete != null) {
                            IconButton(onClick = onDelete) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                            }
                        }
                        TextButton(onClick = onSave, enabled = saveEnabled) { Text(stringResource(R.string.save)) }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
fun YearMenu(year: Int, years: List<Int>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(year.toString(), style = MaterialTheme.typography.titleMedium)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.choose_year))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            years.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.toString()) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
fun DateField(label: String, date: LocalDate, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    ClickableField(
        value = date.formatted(),
        label = label,
        onClick = { open = true },
        trailingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
        modifier = modifier,
    )
    if (open) DatePickerPopup(date, onPick = onChange, onDismiss = { open = false })
}

@Composable
fun DatePickerPopup(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    // Datovelgeren regner i UTC-midnatt.
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                pickerState.selectedDateMillis?.let {
                    onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                }
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        DatePicker(pickerState)
    }
}

@Composable
fun InvoiceStatus.label(): String = stringResource(
    when (this) {
        InvoiceStatus.DRAFT -> R.string.status_draft
        InvoiceStatus.UNPAID -> R.string.status_unpaid
        InvoiceStatus.OVERDUE -> R.string.status_overdue
        InvoiceStatus.PAID -> R.string.status_paid
        InvoiceStatus.CREDITED -> R.string.status_credited
        InvoiceStatus.CREDIT_NOTE -> R.string.status_credit_note
    },
)

@Composable
fun InvoiceStatus.color(): Color = when (this) {
    InvoiceStatus.OVERDUE -> MaterialTheme.colorScheme.error
    InvoiceStatus.PAID -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
