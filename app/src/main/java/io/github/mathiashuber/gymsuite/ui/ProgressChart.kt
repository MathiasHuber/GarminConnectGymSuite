package io.github.mathiashuber.gymsuite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A labelled date range drawn behind the data, e.g. a training era. */
data class ChartBand(val start: LocalDate, val end: LocalDate, val label: String)

/** Round axis ticks covering [min, max]. */
fun niceTicks(min: Double, max: Double, count: Int = 4): List<Double> {
    val lo = if (max > min) min else min - 1
    val hi = if (max > min) max else max + 1
    val rough = (hi - lo) / count
    val mag = 10.0.pow(floor(log10(rough)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= rough - 1e-12 }
    val ticks = mutableListOf<Double>()
    var t = floor(lo / step) * step
    while (t < hi + step * 0.999) {
        ticks += t
        if (t >= hi - 1e-9) break
        t += step
    }
    return ticks
}

/**
 * Line chart of one value per session, x = calendar date.
 * Tap or drag to select a session; records get a larger marker.
 */
@Composable
fun ProgressChart(
    dates: List<LocalDate>,
    values: List<Double>,
    records: List<Boolean>,
    trend: List<Double>?,
    selected: Int?,
    onSelect: (Int) -> Unit,
    formatY: (Double) -> String,
    modifier: Modifier = Modifier,
    bands: List<ChartBand> = emptyList(),
) {
    val colors = MaterialTheme.colorScheme
    val line = colors.primary
    val surface = colors.surface
    val grid = colors.outlineVariant
    val trendColor = colors.onSurfaceVariant
    val textColor = colors.onSurfaceVariant.toArgb()
    val density = LocalDensity.current
    val textPx = with(density) { 11.sp.toPx() }
    val leftPad = with(density) { 44.dp.toPx() }
    val bottomPad = with(density) { 22.dp.toPx() }
    val topPad = with(density) { (if (bands.isEmpty()) 10 else 22).dp.toPx() }
    val bandFill = colors.onSurface.copy(alpha = 0.07f)
    val bandEdge = colors.outlineVariant
    val rightPad = with(density) { 12.dp.toPx() }
    val dateFmt = DateTimeFormatter.ofPattern("MMM yy")

    if (values.isEmpty()) return
    val days = dates.map { it.toEpochDay().toDouble() }
    val xMin = days.first()
    val xMax = if (days.last() > xMin) days.last() else xMin + 1
    val all = values + (trend ?: emptyList())
    val ticks = niceTicks(all.min(), all.max())
    val yMin = ticks.first()
    val yMax = ticks.last().takeIf { it > yMin } ?: (yMin + 1)

    fun nearest(x: Float, width: Float): Int {
        val plotW = width - leftPad - rightPad
        val day = xMin + ((x - leftPad) / plotW).coerceIn(0f, 1f) * (xMax - xMin)
        return days.indices.minBy { abs(days[it] - day) }
    }

    Canvas(
        modifier
            .pointerInput(days) { detectTapGestures { onSelect(nearest(it.x, size.width.toFloat())) } }
            .pointerInput(days) {
                detectHorizontalDragGestures { change, _ -> onSelect(nearest(change.position.x, size.width.toFloat())) }
            },
    ) {
        val plotW = size.width - leftPad - rightPad
        val plotH = size.height - topPad - bottomPad
        fun px(i: Int) = leftPad + ((days[i] - xMin) / (xMax - xMin) * plotW).toFloat()
        fun py(v: Double) = topPad + ((1 - (v - yMin) / (yMax - yMin)) * plotH).toFloat()

        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = textPx
            color = textColor
        }

        // Era bands: alternate shading, a hairline at each start, label on top if it fits.
        bands.forEachIndexed { bi, band ->
            val d0 = maxOf(band.start.toEpochDay().toDouble(), xMin)
            val d1 = minOf(band.end.toEpochDay().toDouble() + 1, xMax)
            if (d1 <= d0) return@forEachIndexed
            val bx0 = leftPad + ((d0 - xMin) / (xMax - xMin) * plotW).toFloat()
            val bx1 = leftPad + ((d1 - xMin) / (xMax - xMin) * plotW).toFloat()
            if (bi % 2 == 0) {
                drawRect(bandFill, Offset(bx0, topPad), androidx.compose.ui.geometry.Size(bx1 - bx0, plotH))
            }
            drawLine(bandEdge, Offset(bx0, topPad - 4.dp.toPx()), Offset(bx0, topPad + plotH), strokeWidth = 1f)
            paint.textAlign = android.graphics.Paint.Align.LEFT
            val room = bx1 - bx0 - 6.dp.toPx()
            if (room > 24.dp.toPx()) {
                var text = band.label
                while (text.length > 3 && paint.measureText(text) > room) text = text.dropLast(2) + "…"
                if (paint.measureText(text) <= room) {
                    drawContext.canvas.nativeCanvas.drawText(text, bx0 + 3.dp.toPx(), topPad - 7.dp.toPx(), paint)
                }
            }
        }

        // Recessive grid and y labels.
        for (t in ticks) {
            val y = py(t)
            drawLine(grid, Offset(leftPad, y), Offset(size.width - rightPad, y), strokeWidth = 1f)
            paint.textAlign = android.graphics.Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(formatY(t), leftPad - 6.dp.toPx(), y + textPx / 3, paint)
        }

        // X labels: first, middle and last date.
        val labelIdx = listOf(0, days.size / 2, days.lastIndex).distinct()
        for (i in labelIdx) {
            paint.textAlign = when (i) {
                0 -> android.graphics.Paint.Align.LEFT
                days.lastIndex -> android.graphics.Paint.Align.RIGHT
                else -> android.graphics.Paint.Align.CENTER
            }
            val x = if (days.size == 1) leftPad else px(i)
            drawContext.canvas.nativeCanvas.drawText(dates[i].format(dateFmt), x, size.height - 4.dp.toPx(), paint)
        }

        fun pathOf(vs: List<Double>) = Path().apply {
            vs.forEachIndexed { i, v -> if (i == 0) moveTo(px(i), py(v)) else lineTo(px(i), py(v)) }
        }

        if (trend != null && trend.size > 1) {
            drawPath(
                pathOf(trend), trendColor,
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)), cap = StrokeCap.Round),
            )
        }
        if (values.size > 1) {
            drawPath(pathOf(values), line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        selected?.takeIf { it in values.indices }?.let { i ->
            drawLine(grid, Offset(px(i), topPad), Offset(px(i), topPad + plotH), strokeWidth = 1.dp.toPx())
        }

        values.forEachIndexed { i, v ->
            val c = Offset(px(i), py(v))
            val r = when {
                i == selected -> 6.dp.toPx()
                records[i] -> 5.dp.toPx()
                else -> 3.dp.toPx()
            }
            drawCircle(surface, r + 2.dp.toPx(), c)
            if (records[i] || i == selected) {
                drawCircle(line, r, c)
            } else {
                drawCircle(line, r, c, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

/** Small legend chip: a sample of the mark followed by a text-colored label. */
@Composable
fun LegendSwatch(color: Color, dashed: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val y = size.height / 2
        drawLine(
            color, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(),
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null,
        )
    }
}
