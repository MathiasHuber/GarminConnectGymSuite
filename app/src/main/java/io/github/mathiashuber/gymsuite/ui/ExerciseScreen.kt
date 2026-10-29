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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mathiashuber.gymsuite.analysis.Metric
import io.github.mathiashuber.gymsuite.analysis.Progression
import io.github.mathiashuber.gymsuite.analysis.SessionPoint
import io.github.mathiashuber.gymsuite.data.WeightUnit
import io.github.mathiashuber.gymsuite.data.exerciseDisplayName
import java.time.LocalDate

private enum class Range(val label: String, val months: Long?) {
    ALL("All", null), YEAR("1 year", 12), HALF("6 months", 6), QUARTER("3 months", 3)
}

private const val TREND_WINDOW = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseScreen(vm: AppViewModel, exerciseKey: String, onBack: () -> Unit) {
    val store by vm.store.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val unit = settings.unit

    val hasWeight = remember(store, exerciseKey) {
        store.workouts.any { w -> w.sets.any { it.exerciseKey == exerciseKey && (it.weightKg ?: 0.0) > 0 } }
    }
    var metric by rememberSaveable(exerciseKey) { mutableStateOf(if (hasWeight) Metric.E1RM else Metric.MAX_REPS) }
    var range by rememberSaveable { mutableStateOf(Range.ALL) }
    var showTrend by rememberSaveable { mutableStateOf(true) }
    var selected by remember(exerciseKey, metric, range) { mutableStateOf<Int?>(null) }

    val allPoints = remember(store, exerciseKey, metric, settings) {
        Progression.progression(store.workouts, exerciseKey, metric, settings.analysis)
    }
    val points = remember(allPoints, range) {
        val from = range.months?.let { LocalDate.now().minusMonths(it) }
        if (from == null) allPoints else allPoints.filter { !it.date.isBefore(from) }
    }
    val scale = if (metric.isWeight) unit.perKg else 1.0
    val values = points.map { it.value * scale }
    // Trend uses the sessions before the range start too, so it doesn't restart at the edge.
    val trend = remember(allPoints, points, scale) {
        val avg = Progression.movingAverage(allPoints, TREND_WINDOW)
        val offset = allPoints.size - points.size
        avg.drop(offset).map { it * scale }
    }
    val formatY: (Double) -> String = { fmtNumber(it) }
    val metrics = if (hasWeight) Metric.entries else listOf(Metric.MAX_REPS, Metric.TOTAL_REPS)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(exerciseDisplayName(exerciseKey), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
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
                        SelectionHeader(points, values, selected, metric, unit)
                        if (points.isEmpty()) {
                            Text("No sessions in this range.", Modifier.padding(vertical = 48.dp))
                        } else {
                            ProgressChart(
                                dates = points.map { it.date },
                                values = values,
                                records = points.map { it.isRecord },
                                trend = if (showTrend && points.size > TREND_WINDOW) trend else null,
                                selected = selected,
                                onSelect = { selected = it },
                                formatY = formatY,
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
            item { Text("Sessions", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall) }
            val newestFirst = points.indices.reversed().toList()
            itemsIndexed(newestFirst, key = { _, i -> points[i].activityId }) { _, i ->
                SessionRow(points[i], values[i], metric, unit, highlighted = i == selected, onClick = { selected = i })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SelectionHeader(points: List<SessionPoint>, values: List<Double>, selected: Int?, metric: Metric, unit: WeightUnit) {
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
}

@Composable
private fun SessionRow(p: SessionPoint, value: Double, metric: Metric, unit: WeightUnit, highlighted: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = if (highlighted) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        headlineContent = { Text(p.date.format(dayFormat) + if (p.isRecord) "  ·  record" else "") },
        supportingContent = { Text(p.sets.joinToString(", ") { fmtSet(it, unit) }) },
        trailingContent = {
            Text(fmtNumber(value) + if (metric.isWeight) " ${unit.label}" else "", style = MaterialTheme.typography.titleSmall)
        },
    )
}
