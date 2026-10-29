package io.github.mathiashuber.gymsuite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mathiashuber.gymsuite.analysis.ClassifiedWorkout
import io.github.mathiashuber.gymsuite.analysis.Era
import io.github.mathiashuber.gymsuite.analysis.EraHighlight
import io.github.mathiashuber.gymsuite.analysis.Metric
import io.github.mathiashuber.gymsuite.analysis.SessionTemplate
import io.github.mathiashuber.gymsuite.analysis.SessionType
import io.github.mathiashuber.gymsuite.data.UserData
import io.github.mathiashuber.gymsuite.data.WeightUnit
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EraDetailScreen(vm: AppViewModel, eraKey: Long, onBack: () -> Unit, onReplace: (Long) -> Unit, onOpenExercise: (String) -> Unit) {
    val result by vm.eras.collectAsStateWithLifecycle()
    val userData by vm.userData.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val res = result
    val era = res?.eraOf(eraKey)
    var renaming by remember { mutableStateOf(false) }
    var splitAt by remember { mutableStateOf<ClassifiedWorkout?>(null) }

    // Edits move era boundaries; follow the era that now contains this one's first session.
    LaunchedEffect(res, eraKey) {
        if (res != null && era == null) {
            val moved = res.eras.firstOrNull { e -> e.workouts.any { it.workout.activityId == eraKey } }
            if (moved != null) onReplace(moved.key) else onBack()
        }
    }
    if (res == null || era == null) return

    val index = res.eras.indexOf(era)
    val previous = res.eras.getOrNull(index - 1)
    val accent = sessionColor(era.dominantType)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rename era") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = accent.copy(alpha = 0.28f)),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item { Hero(era, accent) }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("${era.workouts.size}", "sessions", Modifier.weight(1f))
                    StatTile("%.1f".format(era.perWeek), "per week", Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val typical = if (era.medianSetsPerExercise != null && era.medianReps != null) "${era.medianSetsPerExercise} × ${era.medianReps}" else "–"
                    StatTile(typical, "typical sets × reps", Modifier.weight(1f))
                    StatTile(era.bodyweightShare?.let { "${(it * 100).roundToInt()}%" } ?: "–", "bodyweight sets", Modifier.weight(1f))
                }
            }
            item { Section("Split") { SplitSection(era) } }
            item { Section("Weekly rhythm") { WeekdayRhythm(era.weekdays) } }
            item {
                Section("Calendar") {
                    SessionCalendar(era)
                    Text(
                        "Each square is a day; colored squares are sessions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (era.templates.isNotEmpty()) {
                item { SectionTitle("The plan", "Reconstructed from what you did most often in each kind of session.") }
                items(era.templates, key = { "t" + it.type }) { t ->
                    TemplateCard(t, userData, settings.unit, onOpenExercise)
                }
            }
            if (era.highlights.isNotEmpty()) {
                item {
                    Section("Progress during this era") {
                        era.highlights.forEach { h -> HighlightRow(h, userData, settings.unit, onClick = { onOpenExercise(h.seriesId) }) }
                    }
                }
            }
            item { SectionTitle("Sessions", "Tap a session to start a new era from it.") }
            items(era.workouts.reversed(), key = { "w" + it.workout.activityId }) { c ->
                SessionLine(c, onClick = { if (c != era.workouts.first()) splitAt = c })
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (previous != null) {
                        OutlinedButton(onClick = { vm.updateUserData { it.mergeEraIntoPrevious(era.key) } }, Modifier.fillMaxWidth()) {
                            Text("Merge into previous era (${previous.name})", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(
                        "Era detection: ${era.autoName}. Split ${era.split.lowercase()}, ${era.style.lowercase()} style" +
                            (era.scheme?.let { ", $it rep scheme" } ?: "") + ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (renaming) {
        RenameEraDialog(
            current = era.name,
            auto = era.autoName,
            onDismiss = { renaming = false },
            onSave = { name -> renaming = false; vm.updateUserData { it.renameEra(era.key, name) } },
        )
    }
    splitAt?.let { c ->
        AlertDialog(
            onDismissRequest = { splitAt = null },
            title = { Text("Start a new era here?") },
            text = { Text("Sessions from ${c.date.format(dayFormat)} on become their own era.") },
            confirmButton = {
                TextButton(onClick = {
                    splitAt = null
                    vm.updateUserData { it.startEraAt(c.workout.activityId) }
                }) { Text("Split") }
            },
            dismissButton = { TextButton(onClick = { splitAt = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Hero(era: Era, accent: Color) {
    Box(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), Color.Transparent))),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp)) {
            Text(era.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (era.name != era.autoName) {
                Text(era.autoName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text("${eraRange(era)} · ${eraDuration(era.start, era.end)}", style = MaterialTheme.typography.titleSmall)
            if (era.workoutNames.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "You called these " + era.workoutNames.take(4).joinToString(", ") { "“$it”" },
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    SectionTitle(title)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@Composable
private fun SplitSection(era: Era) {
    MixBar(era.typeMix, Modifier.fillMaxWidth(), height = 14.dp)
    val total = era.typeMix.values.sum().coerceAtLeast(1)
    SessionType.entries.filter { (era.typeMix[it] ?: 0) > 0 }.forEach { t ->
        val c = era.typeMix[t] ?: 0
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(sessionColor(t)))
            Spacer(Modifier.width(8.dp))
            Text(t.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("$c  ·  ${(100.0 * c / total).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (era.rotation != null) {
        Text("Rotation", style = MaterialTheme.typography.labelLarge)
        RotationRow(era.rotation)
    }
}

private fun fmtPlanLoad(sets: Int, reps: Int?, weightKg: Double?, unit: WeightUnit): String =
    "$sets × ${reps ?: "?"}" + (weightKg?.let { " @ ${fmtWeight(it, unit)}" } ?: "")

@Composable
private fun TemplateCard(t: SessionTemplate, userData: UserData, unit: WeightUnit, onOpenExercise: (String) -> Unit) {
    val color = sessionColor(t.type)
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(color))
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${t.type.label} day", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${t.sessions} sessions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (t.names.isNotEmpty()) {
                    Text(
                        t.names.joinToString(", ") { "“$it”" },
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (t.exercises.isEmpty()) {
                    Text("No exercise was repeated often enough to call it part of the plan.", style = MaterialTheme.typography.bodySmall)
                }
                t.exercises.forEachIndexed { i, e ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onOpenExercise(e.seriesId) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}", Modifier.width(22.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text(userData.displayName(e.seriesId), style = MaterialTheme.typography.bodyLarge)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LinearProgressIndicator(
                                    progress = { e.frequency.toFloat() },
                                    modifier = Modifier.width(64.dp).height(4.dp),
                                    color = color,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "in ${(e.frequency * 100).roundToInt()}% of sessions",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(fmtPlanLoad(e.sets, e.reps, e.weightKg, unit), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun HighlightRow(h: EraHighlight, userData: UserData, unit: WeightUnit, onClick: () -> Unit) {
    val weight = h.metric == Metric.E1RM
    fun v(x: Double) = if (weight) fmtWeight(x, unit) else "${fmtNumber(x)} reps"
    val pct = (h.change * 100).roundToInt()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(userData.displayName(h.seriesId), style = MaterialTheme.typography.bodyLarge)
            Text(
                (if (weight) "est. 1RM" else "best set") + " · ${v(h.from)} → ${v(h.to)} · ${h.sessions} sessions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            when {
                pct > 0 -> "▲ $pct%"
                pct < 0 -> "▼ ${-pct}%"
                else -> "= 0%"
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun SessionLine(c: ClassifiedWorkout, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.date.format(dayFormat), style = MaterialTheme.typography.bodyMedium)
            Text(
                c.workout.name.replace("​", "") + " · ${c.workout.sets.size} sets",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TypeChip(c.type)
    }
}

@Composable
private fun RenameEraDialog(current: String, auto: String, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this era") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                Text("Detected as: $auto", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { onSave(null) }) { Text("Use detected name") } },
    )
}
