package app.oikonomos.ui.invoice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.oikonomos.R
import app.oikonomos.data.Customer
import app.oikonomos.data.CustomerType
import app.oikonomos.data.InvoiceRepository
import app.oikonomos.domain.Formats
import app.oikonomos.ui.FormDialog
import app.oikonomos.ui.UiMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CustomersViewModel(private val repository: InvoiceRepository) : ViewModel() {
    val customers: StateFlow<List<Customer>> = repository.observeCustomers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    fun save(customer: Customer) {
        viewModelScope.launch { repository.saveCustomer(customer) }
    }

    /** En kunde med fakturaer kan ikke slettes, for fakturaene skal fortsatt kunne vises. */
    fun delete(customer: Customer) {
        viewModelScope.launch {
            val used = repository.deleteCustomer(customer)
            if (used > 0) messageChannel.send(UiMessage(R.string.customer_in_use, listOf(used)))
        }
    }
}

@Composable
fun CustomersScreen(viewModel: CustomersViewModel, onBack: () -> Unit) {
    val customers by viewModel.customers.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<Customer?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            launch { snackbar.showSnackbar(context.getString(message.id, *message.args.toTypedArray())) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.customers)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = Customer(name = "") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.customer_new)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(customers, key = { it.id }) { customer ->
                val details = listOf(
                    Formats.orgNumber(customer.orgNumber),
                    "${customer.postalCode} ${customer.city}".trim(),
                ).filter { it.isNotBlank() }
                ListItem(
                    modifier = Modifier.clickable { editing = customer },
                    headlineContent = { Text(customer.name) },
                    supportingContent = if (details.isEmpty()) null else ({ Text(details.joinToString(" · ")) }),
                )
            }
            if (customers.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.customers_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }

    editing?.let { customer ->
        CustomerDialog(
            customer = customer,
            onDismiss = { editing = null },
            onSave = {
                editing = null
                viewModel.save(it)
            },
            onDelete = {
                editing = null
                viewModel.delete(customer)
            },
        )
    }
}

/** Skjema for en kunde. [onDelete] er null der sletting ikke tilbys. */
@Composable
fun CustomerDialog(customer: Customer, onDismiss: () -> Unit, onSave: (Customer) -> Unit, onDelete: (() -> Unit)?) {
    val isNew = customer.id == 0L
    var draft by remember { mutableStateOf(customer) }
    val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words)

    FormDialog(
        title = stringResource(if (isNew) R.string.customer_new else R.string.customer_edit),
        onDismiss = onDismiss,
        onSave = {
            onSave(
                draft.copy(
                    name = draft.name.trim(),
                    address = draft.address.trim(),
                    city = draft.city.trim(),
                    reference = draft.reference.trim(),
                ),
            )
        },
        saveEnabled = draft.name.isNotBlank(),
        onDelete = onDelete.takeIf { !isNew },
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = draft.type == CustomerType.BUSINESS,
                onClick = { draft = draft.copy(type = CustomerType.BUSINESS) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text(stringResource(R.string.customer_business)) }
            SegmentedButton(
                selected = draft.type == CustomerType.PRIVATE,
                onClick = { draft = draft.copy(type = CustomerType.PRIVATE, orgNumber = "") },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(stringResource(R.string.customer_private)) }
        }
        OutlinedTextField(
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it) },
            label = { Text(stringResource(R.string.customer_name)) },
            singleLine = true,
            keyboardOptions = words,
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft.type == CustomerType.BUSINESS) {
            OutlinedTextField(
                value = draft.orgNumber,
                onValueChange = { text -> draft = draft.copy(orgNumber = text.filter(Char::isDigit).take(9)) },
                label = { Text(stringResource(R.string.org_number)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = draft.address,
            onValueChange = { draft = draft.copy(address = it) },
            label = { Text(stringResource(R.string.customer_address)) },
            keyboardOptions = words,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = draft.postalCode,
                onValueChange = { text -> draft = draft.copy(postalCode = text.filter(Char::isDigit).take(4)) },
                label = { Text(stringResource(R.string.customer_postal_code)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = draft.city,
                onValueChange = { draft = draft.copy(city = it) },
                label = { Text(stringResource(R.string.customer_city)) },
                singleLine = true,
                keyboardOptions = words,
                modifier = Modifier.weight(3f),
            )
        }
        OutlinedTextField(
            value = draft.email,
            onValueChange = { draft = draft.copy(email = it.trim()) },
            label = { Text(stringResource(R.string.customer_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.reference,
            onValueChange = { draft = draft.copy(reference = it) },
            label = { Text(stringResource(R.string.customer_reference)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
