package io.github.mathiashuber.gymsuite.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mathiashuber.gymsuite.analysis.Progression
import io.github.mathiashuber.gymsuite.data.exerciseDisplayName
import io.github.mathiashuber.gymsuite.garmin.LoginFlow
import java.text.DateFormat
import java.util.Date

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
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    var showImport by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    val summaries = remember(store, settings) { Progression.summarize(store.workouts, settings.analysis) }
    val filtered = remember(summaries, query) {
        summaries.filter { query.isBlank() || exerciseDisplayName(it.key).contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gym Suite") },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") } },
            )
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
            } else if (signedIn && sync == null) {
                item {
                    Text(
                        "No strength workouts stored yet. Tap Sync to download them from Garmin Connect.",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(filtered, key = { it.key }) { s ->
                ListItem(
                    modifier = Modifier.clickable { onOpenExercise(s.key) },
                    headlineContent = { Text(exerciseDisplayName(s.key)) },
                    supportingContent = {
                        Text("${s.sessions} sessions · ${s.totalSets} sets · last ${s.lastDate.format(dayFormat)}")
                    },
                    trailingContent = {
                        s.bestE1rmKg?.let {
                            Column {
                                Text(fmtWeight(it, settings.unit), style = MaterialTheme.typography.titleSmall)
                                Text("best e1RM", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }

    if (showImport) ImportTokensDialog(onDismiss = { showImport = false }, onImport = { vm.importTokens(it); showImport = false })
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
