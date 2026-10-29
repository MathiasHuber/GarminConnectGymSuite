package io.github.mathiashuber.gymsuite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mathiashuber.gymsuite.analysis.ExerciseSummary
import io.github.mathiashuber.gymsuite.analysis.Progression
import io.github.mathiashuber.gymsuite.data.UserData
import io.github.mathiashuber.gymsuite.data.WeightUnit
import io.github.mathiashuber.gymsuite.data.exerciseDisplayName
import io.github.mathiashuber.gymsuite.garmin.LoginFlow
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onLogin: (LoginFlow) -> Unit,
    onOpenExercise: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val store by vm.store.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val userData by vm.userData.collectAsStateWithLifecycle()
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    var showImport by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var showGroupDialog by remember { mutableStateOf(false) }
    val selecting = selection.isNotEmpty()

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }
    BackHandler(enabled = selecting) { selection = emptySet() }

    val summaries = remember(store, settings, userData) {
        Progression.summarize(userData.applyExclusions(store.workouts), settings.analysis, userData::seriesOf)
    }
    val filtered = remember(summaries, query, userData) {
        summaries.filter { s ->
            query.isBlank() || userData.displayName(s.id).contains(query.trim(), ignoreCase = true) ||
                userData.membersOf(s.id).any { exerciseDisplayName(it).contains(query.trim(), ignoreCase = true) }
        }
    }
    val favorites = filtered.filter { it.id in userData.favorites }
    val others = filtered.filter { it.id !in userData.favorites }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Default.Close, "Cancel selection") }
                    },
                    actions = {
                        val allFavorite = selection.all { it in userData.favorites }
                        IconButton(onClick = {
                            vm.updateUserData { it.setFavorite(selection, !allFavorite) }
                            selection = emptySet()
                        }) {
                            Icon(
                                if (allFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                if (allFavorite) "Remove from favorites" else "Add to favorites",
                            )
                        }
                        TextButton(onClick = { showGroupDialog = true }) { Text("Group") }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Gym Suite") },
                    actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") } },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                if (!signedIn) {
                    ConnectCard(onLogin = onLogin, onImport = { showImport = true })
                } else {
                    SyncCard(
                        workouts = store.workouts.size,
                        sets = store.workouts.sumOf { it.sets.size },
                        lastSync = store.lastSyncEpochMs,
                        progress = sync,
                        onSync = { vm.sync(full = false) },
                        onFullSync = { vm.sync(full = true) },
                    )
                }
            }
            if (summaries.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search ${summaries.size} exercises") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        singleLine = true,
                    )
                }
                if (!selecting && userData.groups.isEmpty() && userData.favorites.isEmpty()) {
                    item {
                        Text(
                            "Tip: long-press exercises to select several, then group them (same exercise " +
                                "logged under different names) or mark them as favorites.",
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (signedIn && sync == null) {
                item {
                    Text(
                        "No strength workouts stored yet. Tap Sync to download them from Garmin Connect.",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            fun section(title: String, list: List<ExerciseSummary>) {
                if (list.isEmpty()) return
                item(key = "header:$title") { SectionHeader(title) }
                items(list, key = { it.id }) { s ->
                    ExerciseRow(
                        summary = s,
                        userData = userData,
                        unit = settings.unit,
                        selecting = selecting,
                        selected = s.id in selection,
                        onClick = {
                            if (selecting) selection = selection.toggle(s.id) else onOpenExercise(s.id)
                        },
                        onLongClick = { selection = selection.toggle(s.id) },
                        onToggleFavorite = { vm.updateUserData { it.toggleFavorite(s.id) } },
                    )
                    HorizontalDivider()
                }
            }
            section("Favorites", favorites)
            section(if (favorites.isEmpty()) "Exercises" else "All other exercises", others)
        }
    }

    if (showImport) ImportTokensDialog(onDismiss = { showImport = false }, onImport = { vm.importTokens(it); showImport = false })

    if (showGroupDialog) {
        val existingGroup = selection.firstNotNullOfOrNull { userData.group(it) }
        GroupNameDialog(
            title = "Group ${selection.size} as one exercise",
            initial = existingGroup?.name ?: selection.firstOrNull()?.let { userData.displayName(it) } ?: "",
            members = selection.flatMap { userData.membersOf(it) }.distinct().map(::exerciseDisplayName),
            onDismiss = { showGroupDialog = false },
            onConfirm = { name ->
                val ids = selection
                vm.updateUserData { it.createGroup(UUID.randomUUID().toString(), name, ids) }
                selection = emptySet()
                showGroupDialog = false
            },
        )
    }
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExerciseRow(
    summary: ExerciseSummary,
    userData: UserData,
    unit: WeightUnit,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val group = userData.group(summary.id)
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
        leadingContent = if (selecting) {
            { Checkbox(checked = selected, onCheckedChange = { onClick() }) }
        } else {
            null
        },
        overlineContent = if (group != null) {
            { Text("Group · ${group.members.size} exercises") }
        } else {
            null
        },
        headlineContent = { Text(userData.displayName(summary.id)) },
        supportingContent = {
            Text("${summary.sessions} sessions · ${summary.totalSets} sets · last ${summary.lastDate.format(dayFormat)}")
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                summary.bestE1rmKg?.let {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmtWeight(it, unit), style = MaterialTheme.typography.titleSmall)
                        Text("best e1RM", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (!selecting) {
                    val fav = summary.id in userData.favorites
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            if (fav) "Remove from favorites" else "Add to favorites",
                            tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun ConnectCard(onLogin: (LoginFlow) -> Unit, onImport: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connect Garmin Connect", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sign in on Garmin's own page. The app keeps only the resulting access token, " +
                    "on this phone. Your password never passes through the app.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { onLogin(LoginFlow.MOBILE) }, Modifier.fillMaxWidth()) { Text("Sign in with Garmin") }
            OutlinedButton(onClick = { onLogin(LoginFlow.PORTAL) }, Modifier.fillMaxWidth()) { Text("Use web sign-in instead") }
            TextButton(onClick = onImport, Modifier.fillMaxWidth()) { Text("Import tokens from python-garminconnect") }
        }
    }
}

@Composable
private fun SyncCard(
    workouts: Int,
    sets: Int,
    lastSync: Long?,
    progress: io.github.mathiashuber.gymsuite.data.SyncProgress?,
    onSync: () -> Unit,
    onFullSync: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$workouts workouts · $sets sets", style = MaterialTheme.typography.titleMedium)
            Text(
                lastSync?.let { "Last sync " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
                    ?: "Never synced",
                style = MaterialTheme.typography.bodySmall,
            )
            if (progress != null) {
                Text(progress.message, style = MaterialTheme.typography.bodySmall)
                if (progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { progress.done.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSync) { Text("Sync") }
                    OutlinedButton(onClick = onFullSync) { Text("Full resync") }
                }
            }
        }
    }
}

@Composable
private fun ImportTokensDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import tokens") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste the contents of garmin_tokens.json written by python-garminconnect " +
                        "(see tools/garmin_tokens.py in the repository).",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    placeholder = { Text("{\"di_token\": \"…\", …}") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
