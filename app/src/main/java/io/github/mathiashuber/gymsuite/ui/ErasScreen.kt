package io.github.mathiashuber.gymsuite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mathiashuber.gymsuite.analysis.Era
import io.github.mathiashuber.gymsuite.analysis.Eras
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private sealed interface EraListItem {
    data class Entry(val era: Era) : EraListItem
    data class Break(val from: java.time.LocalDate, val to: java.time.LocalDate) : EraListItem
    data class Sporadic(val eras: List<Era>) : EraListItem
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErasScreen(vm: AppViewModel, onBack: () -> Unit, onOpenEra: (Long) -> Unit) {
    val result by vm.eras.collectAsStateWithLifecycle()
    val userData by vm.userData.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Training eras") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        val res = result
        if (res == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val eras = res.eras
        val main = eras.filter { !it.sporadic }
        val current = res.current
        // Newest first; consecutive sporadic stretches collapse into one row, long pauses get a divider.
        val items = remember(eras) {
            val out = ArrayList<EraListItem>()
            val newestFirst = eras.reversed()
            var i = 0
            while (i < newestFirst.size) {
                val e = newestFirst[i]
                if (e.sporadic) {
                    val run = newestFirst.drop(i).takeWhile { it.sporadic }
                    out += EraListItem.Sporadic(run)
                    i += run.size
                } else {
                    out += EraListItem.Entry(e)
                    i++
                }
                val older = newestFirst.getOrNull(i)
                val newer = newestFirst[i - 1]
                if (older != null && ChronoUnit.DAYS.between(older.end, newer.start) >= Eras.BREAK_DAYS) {
                    out += EraListItem.Break(older.end, newer.start)
                }
            }
            out
        }

        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (eras.isEmpty()) {
                            Text("Sync some strength workouts to see your training eras.")
                        } else {
                            val years = ChronoUnit.MONTHS.between(eras.first().start, eras.last().end) / 12.0
                            Text(
                                "${main.size} eras over ${if (years >= 1) "%.0f years".format(years) else eraDuration(eras.first().start, eras.last().end)}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            EraTimeline(
                                eras,
                                Modifier.fillMaxWidth().height(96.dp),
                                highlight = current?.key,
                                onEraClick = { onOpenEra(it.key) },
                            )
                            TypeLegend(eras.flatMap { it.typeMix.keys }.toSet())
                            Text(
                                "Each block is an era, stacked by its mix of session types. Tap one to see the plan you followed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            items(items, key = {
                when (it) {
                    is EraListItem.Entry -> "e" + it.era.key
                    is EraListItem.Break -> "b" + it.from
                    is EraListItem.Sporadic -> "s" + it.eras.first().key
                }
            }) { item ->
                when (item) {
                    is EraListItem.Entry -> EraCard(item.era, isCurrent = item.era.key == current?.key, onClick = { onOpenEra(item.era.key) })
                    is EraListItem.Break -> BreakDivider(item.from, item.to)
                    is EraListItem.Sporadic -> SporadicRow(item.eras)
                }
            }
            item { DetectionCard(detail = userData.eraDetail, hasEdits = userData.eraNames.isNotEmpty() || userData.eraSplits.isNotEmpty() || userData.eraMerges.isNotEmpty(), vm = vm) }
        }
    }
}

@Composable
private fun EraCard(era: Era, isCurrent: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        MixBar(era.typeMix, Modifier.fillMaxWidth(), height = 6.dp)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(era.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${eraRange(era)} · ${eraDuration(era.start, era.end)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isCurrent) {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            "Now",
                            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MiniStat("${era.workouts.size}", "sessions")
                MiniStat("%.1f".format(era.perWeek), "per week")
                era.medianReps?.let { MiniStat("$it", "typ. reps") }
                era.bodyweightShare?.let { MiniStat("${(it * 100).roundToInt()}%", "bodyweight") }
            }
            if (era.rotation != null) RotationRow(era.rotation) else TypeLegend(era.typeMix.keys)
            if (era.workoutNames.isNotEmpty()) {
                Text(
                    era.workoutNames.take(3).joinToString("  ·  ") { "“$it”" },
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MiniStat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BreakDivider(from: java.time.LocalDate, to: java.time.LocalDate) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            "  break · ${eraDuration(from.plusDays(1), to.minusDays(1))}  ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable
private fun SporadicRow(eras: List<Era>) {
    val sessions = eras.sumOf { it.workouts.size }
    val range = if (eras.size == 1) eraRange(eras.first()) else "${eraRange(eras.last())} – ${eraRange(eras.first())}"
    Text(
        "$sessions sporadic session${if (sessions == 1) "" else "s"} · $range",
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DetectionCard(detail: Int, hasEdits: Boolean, vm: AppViewModel) {
    var value by remember(detail) { mutableFloatStateOf(detail.toFloat()) }
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Era detection", style = MaterialTheme.typography.titleSmall)
            Text(
                "Eras are found where your exercise selection and session types change. A pause of " +
                    "${Eras.BREAK_DAYS / 7}+ weeks always starts a new era.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Slider(
                value = value,
                onValueChange = { value = it },
                onValueChangeFinished = { vm.updateUserData { it.copy(eraDetail = value.roundToInt()) } },
                valueRange = 1f..5f,
                steps = 3,
            )
            Row {
                Text("Fewer, longer eras", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("More, shorter eras", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (hasEdits) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { vm.updateUserData { it.resetEras() } }) { Text("Reset my era names and edits") }
            }
        }
    }
}
