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
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.oikonomos.R
import app.oikonomos.data.Project
import app.oikonomos.data.VoucherRepository
import app.oikonomos.ui.FormDialog
import app.oikonomos.ui.UiMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProjectsViewModel(private val repository: VoucherRepository) : ViewModel() {
    val projects: StateFlow<List<Project>> = repository.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    fun save(project: Project) {
        viewModelScope.launch { repository.saveProject(project) }
    }

    /** Et prosjekt som er brukt, kan ikke slettes, for da ville bilagene mistet tilknytningen sin. */
    fun delete(project: Project) {
        viewModelScope.launch {
            val used = repository.deleteProject(project)
            if (used > 0) messageChannel.send(UiMessage(R.string.project_in_use, listOf(used)))
        }
    }
}

@Composable
fun ProjectsScreen(viewModel: ProjectsViewModel, onBack: () -> Unit) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<Project?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            launch { snackbar.showSnackbar(context.getString(message.id, *message.args.toTypedArray())) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.projects)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = Project(name = "") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.project_new)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Text(
                    stringResource(R.string.projects_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(projects, key = { it.id }) { project ->
                val details = listOfNotNull(
                    stringResource(R.string.category_inactive).takeIf { !project.active },
                    project.note.takeIf { it.isNotBlank() },
                )
                ListItem(
                    modifier = Modifier.clickable { editing = project },
                    headlineContent = {
                        Text(
                            project.name,
                            color = if (project.active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    supportingContent = if (details.isEmpty()) null else ({
                        Text(details.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }),
                )
            }
        }
    }

    editing?.let { project ->
        var draft by remember(project) { mutableStateOf(project) }
        FormDialog(
            title = stringResource(if (project.id == 0L) R.string.project_new else R.string.project_edit),
            onDismiss = { editing = null },
            onSave = {
                editing = null
                viewModel.save(draft.copy(name = draft.name.trim(), note = draft.note.trim()))
            },
            saveEnabled = draft.name.isNotBlank(),
            onDelete = {
                editing = null
                viewModel.delete(project)
            }.takeIf { project.id != 0L },
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text(stringResource(R.string.project_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.note,
                onValueChange = { draft = draft.copy(note = it) },
                label = { Text(stringResource(R.string.project_note)) },
                supportingText = { Text(stringResource(R.string.project_note_hint)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (project.id != 0L) {
                Row(
                    Modifier.fillMaxWidth().clickable { draft = draft.copy(active = !draft.active) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.category_active))
                        Text(
                            stringResource(R.string.project_active_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = draft.active, onCheckedChange = null)
                }
            }
        }
    }
}
