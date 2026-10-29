package io.github.mathiashuber.gymsuite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mathiashuber.gymsuite.analysis.Metric
import io.github.mathiashuber.gymsuite.analysis.Progression
import io.github.mathiashuber.gymsuite.analysis.SessionPoint
import io.github.mathiashuber.gymsuite.data.UserData
import io.github.mathiashuber.gymsuite.data.WeightUnit
import io.github.mathiashuber.gymsuite.data.Workout
import io.github.mathiashuber.gymsuite.data.exerciseDisplayName
import java.time.LocalDate
import java.util.UUID

private enum class Range(val label: String, val months: Long?) {
    ALL("All", null), YEAR("1 year", 12), HALF("6 months", 6), QUARTER("3 months", 3)
}

private const val TREND_WINDOW = 5

/**
 * Progress of one series: a single Garmin exercise or a user group.
 * [onReplace] swaps this screen for another series, e.g. when the exercise
 * shown here has just been added to a group.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseScreen(vm: AppViewModel, seriesId: String, onBack: () -> Unit, onReplace: (String) -> Unit) {
    val store by vm.store.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val userData by vm.userData.collectAsStateWithLifecycle()
    val unit = settings.unit
    val group = userData.group(seriesId)
    val keys = userData.membersOf(seriesId)
    var showEdit by remember { mutableStateOf(false) }
    var showAddToGroup by remember { mutableStateOf(false) }
    var showNewGroup by remember { mutableStateOf(false) }

    LaunchedEffect(userData, seriesId) {
        if (seriesId.startsWith(UserData.GROUP_PREFIX)) {
            if (group == null) onBack()
        } else {
            val now = userData.seriesOf(seriesId)
            if (now != seriesId) onReplace(now)
        }
    }

    val hasWeight = remember(store, keys) {
        store.workouts.any { w -> w.sets.any { it.exerciseKey in keys && (it.weightKg ?: 0.0) > 0 } }
    }
    var metric by rememberSaveable(seriesId) { mutableStateOf(if (hasWeight) Metric.E1RM else Metric.MAX_REPS) }
    var range by rememberSaveable { mutableStateOf(Range.ALL) }
    var showTrend by rememberSaveable { mutableStateOf(true) }
    var selectedId by remember(seriesId, metric, range) { mutableStateOf<Long?>(null) }
    var expanded by remember(seriesId) { mutableStateOf(emptySet<Long>()) }

    val from = range.months?.let { LocalDate.now().minusMonths(it) }
    val allPoints = remember(store, userData, keys, metric, settings) {
        Progression.progression(userData.applyExclusions(store.workouts), keys, metric, settings.analysis)
    }
    val points = remember(allPoints, from) { if (from == null) allPoints else allPoints.filter { !it.date.isBefore(from) } }
    val pointById = points.associateBy { it.activityId }
    // Every session with this exercise, excluded ones included, so they can be restored.
    val sessions = remember(store, keys, from) {
        store.workouts
            .filter { w -> w.sets.any { it.exerciseKey in keys } }
            .filter { w -> from == null || runCatching { !LocalDate.parse(w.date).isBefore(from) }.getOrDefault(true) }
            .sortedByDescending { it.startTimeLocal }
    }
    val scale = if (metric.isWeight) unit.perKg else 1.0
    val values = points.map { it.value * scale }
    // Trend uses the sessions before the range start too, so it doesn't restart at the edge.
    val trend = remember(allPoints, points, scale) {
        Progression.movingAverage(allPoints, TREND_WINDOW).drop(allPoints.size - points.size).map { it * scale }
    }
    val selected = points.indexOfFirst { it.activityId == selectedId }.takeIf { it >= 0 }
    val metrics = if (hasWeight) Metric.entries else listOf(Metric.MAX_REPS, Metric.TOTAL_REPS)
    val title = userData.displayName(seriesId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    val fav = seriesId in userData.favorites
                    IconButton(onClick = { vm.updateUserData { it.toggleFavorite(seriesId) } }) {
                        Icon(
                            if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            if (fav) "Remove from favorites" else "Add to favorites",
                        )
                    }
                    IconButton(onClick = { if (group != null) showEdit = true else showAddToGroup = true }) {
                        Icon(Icons.Default.Edit, if (group != null) "Edit group" else "Add to group")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (group != null) {
                item {
                    Text(
                        "Group of " + group.members.joinToString(", ") { exerciseDisplayName(it) },
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    metrics.forEach { m ->
                        FilterChip(selected = m == metric, onClick = { metric = m }, label = { Text(m.label) })
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Range.entries.forEach { r ->
                        FilterChip(selected = r == range, onClick = { range = r }, label = { Text(r.label) })
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        val unitLabel = if (metric.isWeight) " (${unit.label})" else ""
                        Text(metric.label + unitLabel, style = MaterialTheme.typography.titleSmall)
                        if (metric == Metric.E1RM) {
                            Text(
                                "${settings.analysis.formula.label}, sets of ≤${settings.analysis.maxRepsForE1rm} reps",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        SelectionHeader(
                            points, values, selected, unit,
                            onExcludeSet = { p ->
                                p.bestSet?.let { s -> vm.updateUserData { it.toggleSet(p.activityId, s.index) } }
                            },
                        )
                        if (points.isEmpty()) {
                            Text("No sessions in this range.", Modifier.padding(vertical = 48.dp))
                        } else {
                            ProgressChart(
                                dates = points.map { it.date },
                                values = values,
                                records = points.map { it.isRecord },
                                trend = if (showTrend && points.size > TREND_WINDOW) trend else null,
                                selected = selected,
                                onSelect = { selectedId = points[it].activityId },
                                formatY = { fmtNumber(it) },
                                modifier = Modifier.fillMaxWidth().height(260.dp),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                LegendSwatch(MaterialTheme.colorScheme.primary, dashed = false, modifier = Modifier.size(18.dp, 12.dp))
                                Text("per session · filled dot = record", style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.width(8.dp))
                                LegendSwatch(MaterialTheme.colorScheme.onSurfaceVariant, dashed = true, modifier = Modifier.size(18.dp, 12.dp))
                                Text("$TREND_WINDOW-session avg", style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.weight(1f))
                                Switch(checked = showTrend, onCheckedChange = { showTrend = it })
                            }
                        }
                    }
                }
            }
            item {
                Text("Sessions", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall)
                Text(
                    "Tap a session to exclude single sets or the whole workout from the analysis.",
                    Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(sessions, key = { it.activityId }) { w ->
                val p = pointById[w.activityId]
                SessionRow(
                    workout = w,
                    keys = keys,
                    showExerciseNames = keys.size > 1,
                    point = p,
                    value = p?.let { it.value * scale },
                    metric = metric,
                    unit = unit,
                    userData = userData,
                    highlighted = w.activityId == selectedId,
                    expanded = w.activityId in expanded,
                    onClick = {
                        expanded = if (w.activityId in expanded) expanded - w.activityId else expanded + w.activityId
                        if (p != null) selectedId = w.activityId
                    },
                    onToggleSet = { index -> vm.updateUserData { it.toggleSet(w.activityId, index) } },
                    onToggleWorkout = { vm.updateUserData { it.toggleWorkout(w.activityId) } },
                )
                HorizontalDivider()
            }
        }
    }

    if (showEdit && group != null) {
        EditGroupDialog(
            group = group,
            onDismiss = { showEdit = false },
            onRename = { name -> vm.updateUserData { it.renameGroup(group.id, name) } },
            onRemoveMember = { key -> vm.updateUserData { it.removeFromGroup(group.id, key) } },
            onUngroup = {
                showEdit = false
                vm.updateUserData { it.deleteGroup(group.id) }
            },
        )
    }
    if (showAddToGroup) {
        AddToGroupDialog(
            exerciseName = title,
            groups = userData.groups,
            onDismiss = { showAddToGroup = false },
            onAddTo = { g ->
                showAddToGroup = false
                vm.updateUserData { it.addToGroup(g.id, seriesId) }
            },
            onNewGroup = { showAddToGroup = false; showNewGroup = true },
        )
    }
    if (showNewGroup) {
        GroupNameDialog(
            title = "New group",
            initial = title,
            members = listOf(title),
            onDismiss = { showNewGroup = false },
            onConfirm = { name ->
                showNewGroup = false
                vm.updateUserData { it.createGroup(UUID.randomUUID().toString(), name, listOf(seriesId)) }
            },
        )
    }
}

@Composable
private fun SelectionHeader(
    points: List<SessionPoint>,
    values: List<Double>,
    selected: Int?,
    unit: WeightUnit,
    onExcludeSet: (SessionPoint) -> Unit,
) {
    if (points.isEmpty()) return
    val i = selected ?: points.lastIndex
    val first = values.first()
    val best = values.max()
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column {
            Text(if (selected == null) "Latest" else points[i].date.format(dayFormat), style = MaterialTheme.typography.labelSmall)
            Text(fmtNumber(values[i]), style = MaterialTheme.typography.headlineSmall)
            points[i].bestSet?.let { Text(fmtSet(it, unit), style = MaterialTheme.typography.bodySmall) }
        }
        Column {
            Text("Best", style = MaterialTheme.typography.labelSmall)
            Text(fmtNumber(best), style = MaterialTheme.typography.headlineSmall)
        }
        if (points.size > 1 && first > 0) {
            Column {
                Text("Change", style = MaterialTheme.typography.labelSmall)
                val pct = (values[i] / first - 1) * 100
                Text((if (pct >= 0) "+" else "") + "%.0f%%".format(pct), style = MaterialTheme.typography.headlineSmall)
                Text("since ${points.first().date.format(dayFormat)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (selected != null && points[i].bestSet != null) {
        TextButton(onClick = { onExcludeSet(points[i]) }) { Text("Exclude this set (outlier)") }
    }
}

@Composable
private fun SessionRow(
    workout: Workout,
    keys: Set<String>,
    showExerciseNames: Boolean,
    point: SessionPoint?,
    value: Double?,
    metric: Metric,
    unit: WeightUnit,
    userData: UserData,
    highlighted: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    onToggleSet: (Int) -> Unit,
    onToggleWorkout: () -> Unit,
) {
    val sets = workout.sets.filter { it.exerciseKey in keys }
    val workoutExcluded = userData.isWorkoutExcluded(workout.activityId)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val date = runCatching { LocalDate.parse(workout.date).format(dayFormat) }.getOrDefault(workout.date)
    Surface(color = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        date + if (point?.isRecord == true) "  ·  record" else "",
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (workoutExcluded) TextDecoration.LineThrough else null,
                    )
                    Text(workout.name, style = MaterialTheme.typography.bodySmall, color = muted)
                }
                Text(
                    when {
                        workoutExcluded -> "excluded"
                        value == null -> "–"
                        else -> fmtNumber(value) + if (metric.isWeight) " ${unit.label}" else ""
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (workoutExcluded || value == null) muted else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (!expanded) {
                Text(
                    buildAnnotatedString {
                        sets.forEachIndexed { n, s ->
                            if (n > 0) append(", ")
                            if (userData.isSetExcluded(workout.activityId, s)) {
                                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = muted)) { append(fmtSet(s, unit)) }
                            } else {
                                append(fmtSet(s, unit))
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                sets.forEach { s ->
                    val excluded = userData.isSetExcluded(workout.activityId, s)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = !excluded,
                            onCheckedChange = { onToggleSet(s.index) },
                            enabled = !workoutExcluded,
                        )
                        Column {
                            Text(
                                fmtSet(s, unit),
                                textDecoration = if (excluded) TextDecoration.LineThrough else null,
                                color = if (excluded) muted else MaterialTheme.colorScheme.onSurface,
                            )
                            if (showExerciseNames) {
                                Text(exerciseDisplayName(s.exerciseKey), style = MaterialTheme.typography.bodySmall, color = muted)
                            }
                        }
                    }
                }
                TextButton(onClick = onToggleWorkout) {
                    Text(if (workoutExcluded) "Include this workout again" else "Exclude whole workout")
                }
            }
        }
    }
}
