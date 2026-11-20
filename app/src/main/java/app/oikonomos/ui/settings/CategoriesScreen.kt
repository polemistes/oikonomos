package app.oikonomos.ui.settings

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import app.oikonomos.data.Category
import app.oikonomos.data.CategoryDao
import app.oikonomos.data.VoucherType
import app.oikonomos.ui.UiMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CategoriesViewModel(private val dao: CategoryDao) : ViewModel() {
    val categories: StateFlow<List<Category>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    fun save(category: Category) {
        viewModelScope.launch {
            if (category.id == 0L) dao.insert(category) else dao.update(category)
        }
    }

    /** En kategori som er brukt på bilag, kan ikke slettes, for da ville bilagene mistet kategorien sin. */
    fun delete(category: Category) {
        viewModelScope.launch {
            val used = dao.usageCount(category.id)
            if (used > 0) {
                messageChannel.send(UiMessage(R.string.category_in_use, listOf(used)))
            } else {
                dao.delete(category)
            }
        }
    }
}

@Composable
fun CategoriesScreen(viewModel: CategoriesViewModel, onBack: () -> Unit) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<Category?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            launch { snackbar.showSnackbar(context.getString(message.id, *message.args.toTypedArray())) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.categories)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = Category(name = "", type = VoucherType.EXPENSE) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.category_new)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            for (type in listOf(VoucherType.INCOME, VoucherType.EXPENSE)) {
                item(key = type.name) {
                    Text(
                        stringResource(if (type == VoucherType.INCOME) R.string.income else R.string.expenses),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
                    )
                }
                items(categories.filter { it.type == type }, key = { it.id }) { category ->
                    val details = listOfNotNull(
                        category.account.takeIf { it.isNotBlank() },
                        stringResource(R.string.category_inactive).takeIf { !category.active },
                    )
                    ListItem(
                        modifier = Modifier.clickable { editing = category },
                        headlineContent = {
                            Text(
                                category.name,
                                color = if (category.active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        supportingContent = if (details.isEmpty()) null else ({ Text(details.joinToString(" · ")) }),
                    )
                }
            }
        }
    }

    editing?.let { category ->
        CategoryDialog(
            category = category,
            onDismiss = { editing = null },
            onSave = {
                editing = null
                viewModel.save(it)
            },
            onDelete = {
                editing = null
                viewModel.delete(category)
            },
        )
    }
}

@Composable
private fun CategoryDialog(category: Category, onDismiss: () -> Unit, onSave: (Category) -> Unit, onDelete: () -> Unit) {
    val isNew = category.id == 0L
    var name by remember { mutableStateOf(category.name) }
    var account by remember { mutableStateOf(category.account) }
    var type by remember { mutableStateOf(category.type) }
    var active by remember { mutableStateOf(category.active) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.category_new else R.string.category_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Typen velges bare for nye kategorier, så bilag aldri får en kategori av feil type.
                if (isNew) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = type == VoucherType.EXPENSE,
                            onClick = { type = VoucherType.EXPENSE },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text(stringResource(R.string.type_expense)) }
                        SegmentedButton(
                            selected = type == VoucherType.INCOME,
                            onClick = { type = VoucherType.INCOME },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text(stringResource(R.string.type_income)) }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.category_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = account,
                    onValueChange = { text -> account = text.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.category_account)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!isNew) {
                    Row(
                        Modifier.fillMaxWidth().clickable { active = !active },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.category_active))
                            Text(
                                stringResource(R.string.category_active_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = active, onCheckedChange = null)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(category.copy(name = name.trim(), account = account, type = type, active = active)) },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
