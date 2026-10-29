package io.github.mathiashuber.gymsuite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mathiashuber.gymsuite.analysis.Era
import io.github.mathiashuber.gymsuite.analysis.SessionType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Session-type colors: the first six categorical slots of the validated reference palette, in fixed order. */
@Composable
fun sessionColor(type: SessionType): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (type) {
        SessionType.PUSH -> Color(if (dark) 0xFF3987E5 else 0xFF2A78D6)
        SessionType.PULL -> Color(if (dark) 0xFFD95926 else 0xFFEB6834)
        SessionType.LEGS -> Color(if (dark) 0xFF199E70 else 0xFF1BAF7A)
        SessionType.UPPER -> Color(if (dark) 0xFFC98500 else 0xFFEDA100)
        SessionType.FULL_BODY -> Color(if (dark) 0xFFD55181 else 0xFFE87BA4)
        SessionType.CONDITIONING -> Color(0xFF008300)
        SessionType.UNCLASSIFIED -> MaterialTheme.colorScheme.outline
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
fun sessionColors(): Map<SessionType, Color> = SessionType.entries.associateWith { sessionColor(it) }

private val monthYear = DateTimeFormatter.ofPattern("MMM yyyy")

fun eraRange(era: Era): String {
    val a = era.start.format(monthYear)
    val b = era.end.format(monthYear)
    return if (a == b) a else "$a – $b"
}

fun eraDuration(start: LocalDate, end: LocalDate): String {
    val days = ChronoUnit.DAYS.between(start, end) + 1
    val months = ChronoUnit.MONTHS.between(start, end.plusDays(1))
    return when {
        days < 14 -> "$days days"
        months < 2 -> "${(days + 3) / 7} weeks"
        months < 12 -> "$months months"
        else -> {
            val y = months / 12
            val m = months % 12
            (if (y == 1L) "1 year" else "$y years") + if (m > 0) " $m mo" else ""
        }
    }
}

/** Proportional bar of session types, with 2dp surface gaps between segments. */
@Composable
fun MixBar(mix: Map<SessionType, Int>, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val total = mix.values.sum().coerceAtLeast(1)
    Row(modifier.height(height).clip(RoundedCornerShape(height / 2)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        SessionType.entries.filter { (mix[it] ?: 0) > 0 }.forEach { t ->
            Box(Modifier.weight((mix[t] ?: 0).toFloat() / total).fillMaxHeight().background(sessionColor(t)))
        }
    }
}

/** Colored dot followed by a text-colored label: identity is never color alone. */
@Composable
fun TypeChip(type: SessionType, text: String = type.label) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(sessionColor(type)))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RotationRow(rotation: List<SessionType>) {
    FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        rotation.forEachIndexed { i, t ->
            if (i > 0) Text("→", Modifier.align(Alignment.CenterVertically), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TypeChip(t)
        }
        Text("↻", Modifier.align(Alignment.CenterVertically).padding(start = 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TypeLegend(types: Collection<SessionType>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SessionType.entries.filter { it in types }.forEach { TypeChip(it) }
    }
}

/**
 * All eras on one time axis. Each era is a block stacked by its session-type
 * mix; sporadic stretches are small dots; years are marked below.
 */
@Composable
fun EraTimeline(
    eras: List<Era>,
    modifier: Modifier = Modifier,
    highlight: Long? = null,
    showYears: Boolean = true,
    onEraClick: ((Era) -> Unit)? = null,
) {
    if (eras.isEmpty()) return
    val colors = sessionColors()
    val outline = MaterialTheme.colorScheme.outline
    val grid = MaterialTheme.colorScheme.outlineVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val density = LocalDensity.current
    val labelPx = with(density) { 10.sp.toPx() }
    val start = eras.first().start.withDayOfYear(1)
    val end = eras.last().end.plusDays(14)
    val span = ChronoUnit.DAYS.between(start, end).toFloat().coerceAtLeast(1f)

    Canvas(
        modifier.pointerInput(eras) {
            if (onEraClick == null) return@pointerInput
            detectTapGestures { pos ->
                val day = start.plusDays((pos.x / size.width * span).toLong())
                eras.filter { !it.sporadic }
                    .minByOrNull { e -> if (day in e.start..e.end) 0 else minOf(ChronoUnit.DAYS.between(day, e.start).let { kotlin.math.abs(it) }, ChronoUnit.DAYS.between(e.end, day).let { kotlin.math.abs(it) }) }
                    ?.let(onEraClick)
            }
        },
    ) {
        val axisH = if (showYears) labelPx + 6.dp.toPx() else 0f
        val barTop = 0f
        val barH = size.height - axisH
        fun x(d: LocalDate) = ChronoUnit.DAYS.between(start, d) / span * size.width
        val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = labelPx; color = labelColor }

        // Year ticks, labelled every year (or every other year on long histories).
        var y = start
        val years = ChronoUnit.YEARS.between(start, end) + 1
        var n = 0
        while (!y.isAfter(end)) {
            val xx = x(y)
            drawLine(grid, Offset(xx, barTop), Offset(xx, barTop + barH), strokeWidth = 1f)
            if (showYears && (years <= 6 || n % 2 == 0)) {
                drawContext.canvas.nativeCanvas.drawText(y.year.toString(), xx + 2.dp.toPx(), size.height - 2.dp.toPx(), paint)
            }
            y = y.plusYears(1)
            n++
        }

        val gap = 1.dp.toPx()
        for (era in eras) {
            val x0 = x(era.start) + gap
            val x1 = maxOf(x(era.end.plusDays(7)) - gap, x0 + 3.dp.toPx())
            if (era.sporadic) {
                drawCircle(outline, 2.5.dp.toPx(), Offset((x0 + x1) / 2, barTop + barH / 2))
                continue
            }
            val total = era.typeMix.values.sum().toFloat()
            var top = barTop
            SessionType.entries.forEach { t ->
                val c = era.typeMix[t] ?: return@forEach
                val h = barH * c / total
                drawRoundRect(
                    colors.getValue(t),
                    topLeft = Offset(x0, top),
                    size = Size(x1 - x0, (h - 1f).coerceAtLeast(1f)),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
                top += h
            }
            if (era.key == highlight) {
                drawRoundRect(
                    onSurface,
                    topLeft = Offset(x0 - 2.dp.toPx(), barTop - 1f),
                    size = Size(x1 - x0 + 4.dp.toPx(), barH + 2f),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
    }
}

/** GitHub-style grid: one column per week, one row per weekday, cells colored by session type. */
@Composable
fun SessionCalendar(era: Era, onDayClick: (Long) -> Unit = {}) {
    val colors = sessionColors()
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val density = LocalDensity.current
    val first = era.start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weeks = (ChronoUnit.DAYS.between(first, era.end) / 7 + 1).toInt()
    val byDay = era.workouts.groupBy { it.date }
    val cell = 14.dp
    val step = 17.dp
    val labelPx = with(density) { 10.sp.toPx() }
    val monthFmt = DateTimeFormatter.ofPattern("MMM")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Canvas(
            Modifier
                .width(step * weeks + 4.dp)
                .height(step * 7 + 16.dp)
                .pointerInput(era.key) {
                    detectTapGestures { pos ->
                        val w = (pos.x / step.toPx()).toInt()
                        val d = ((pos.y - 16.dp.toPx()) / step.toPx()).toInt()
                        if (d in 0..6) byDay[first.plusDays(w * 7L + d)]?.firstOrNull()?.let { onDayClick(it.workout.activityId) }
                    }
                },
        ) {
            val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = labelPx; color = labelColor }
            var lastMonth = -1
            for (w in 0 until weeks) {
                val weekStart = first.plusDays(w * 7L)
                if (weekStart.monthValue != lastMonth) {
                    drawContext.canvas.nativeCanvas.drawText(weekStart.format(monthFmt), w * step.toPx(), labelPx, paint)
                    lastMonth = weekStart.monthValue
                }
                for (d in 0..6) {
                    val day = weekStart.plusDays(d.toLong())
                    val type = byDay[day]?.firstOrNull()?.type
                    val outside = day.isBefore(era.start) || day.isAfter(era.end)
                    drawRoundRect(
                        color = when {
                            type != null -> colors.getValue(type)
                            outside -> empty.copy(alpha = 0.35f)
                            else -> empty
                        },
                        topLeft = Offset(w * step.toPx(), 16.dp.toPx() + d * step.toPx()),
                        size = Size(cell.toPx(), cell.toPx()),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                    )
                }
            }
        }
    }
}

/** Seven dots sized by how often each weekday was trained. */
@Composable
fun WeekdayRhythm(counts: IntArray) {
    val max = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
    val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        counts.forEachIndexed { i, c ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                    val d = (10 + 24f * c / max).dp
                    Box(
                        Modifier.size(d).clip(CircleShape).background(
                            if (c == 0) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f + 0.65f * c / max),
                        ),
                    )
                }
                Text(labels[i], style = MaterialTheme.typography.labelSmall)
                Text("$c", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Big number with a caption, for the era header grid. */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
