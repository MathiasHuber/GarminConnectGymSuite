package io.github.mathiashuber.gymsuite.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mathiashuber.gymsuite.analysis.E1rmFormula
import io.github.mathiashuber.gymsuite.data.WeightUnit
import java.time.LocalDate
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val today = LocalDate.now()

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(vm::exportCsv)
    }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::exportJson)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Weight unit", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WeightUnit.entries.forEach { u ->
                    FilterChip(
                        selected = settings.unit == u,
                        onClick = { vm.updateSettings(settings.copy(unit = u)) },
                        label = { Text(u.label) },
                    )
                }
            }
            HorizontalDivider()

            Text("Estimated 1RM formula", style = MaterialTheme.typography.titleSmall)
            E1rmFormula.entries.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = settings.analysis.formula == f,
                        onClick = { vm.updateSettings(settings.copy(analysis = settings.analysis.copy(formula = f))) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.analysis.formula == f, onClick = null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(f.label)
                        Text(
                            "5 × 100 → ${fmtNumber(f.estimate(100.0, 5))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text("Ignore sets above ${settings.analysis.maxRepsForE1rm} reps for e1RM", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = settings.analysis.maxRepsForE1rm.toFloat(),
                onValueChange = {
                    vm.updateSettings(settings.copy(analysis = settings.analysis.copy(maxRepsForE1rm = it.roundToInt())))
                },
                valueRange = 1f..30f,
                steps = 28,
            )
            Text(
                "Estimates from high-rep sets are unreliable; 10–12 is a common cut-off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()

            Text("Your data", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = { csvLauncher.launch("garmin_strength_sets_$today.csv") }, Modifier.fillMaxWidth()) {
                Text("Export all sets as CSV")
            }
            OutlinedButton(onClick = { jsonLauncher.launch("garmin_strength_$today.json") }, Modifier.fillMaxWidth()) {
                Text("Export as JSON")
            }
            OutlinedButton(onClick = { confirmDelete = true }, Modifier.fillMaxWidth()) { Text("Delete local data") }
            if (signedIn) {
                OutlinedButton(onClick = vm::signOut, Modifier.fillMaxWidth()) { Text("Sign out of Garmin") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete local data?") },
            text = { Text("Removes the downloaded workouts from this phone. Your Garmin Connect account is not touched; a sync downloads them again.") },
            confirmButton = { TextButton(onClick = { vm.clearData(); confirmDelete = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
