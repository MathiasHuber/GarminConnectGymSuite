package io.github.mathiashuber.gymsuite.analysis

import io.github.mathiashuber.gymsuite.data.StrengthSet
import io.github.mathiashuber.gymsuite.data.Workout
import java.time.LocalDate

enum class E1rmFormula(val label: String) {
    EPLEY("Epley"),
    BRZYCKI("Brzycki"),
    LOMBARDI("Lombardi"),
    AVERAGE("Average of all");

    /** Estimated one-rep max for [reps] reps at [weight]; a single is its own max. */
    fun estimate(weight: Double, reps: Int): Double {
        require(reps >= 1)
        if (reps == 1) return weight
        return when (this) {
            EPLEY -> weight * (1 + reps / 30.0)
            // Brzycki diverges at 37 reps; cap so very high rep sets stay finite.
            BRZYCKI -> weight * 36.0 / (37.0 - reps.coerceAtMost(36))
            LOMBARDI -> weight * Math.pow(reps.toDouble(), 0.10)
            AVERAGE -> listOf(EPLEY, BRZYCKI, LOMBARDI).map { it.estimate(weight, reps) }.average()
        }
    }
}

enum class Metric(val label: String, val isWeight: Boolean) {
    E1RM("Est. 1RM", true),
    MAX_WEIGHT("Top weight", true),
    VOLUME("Volume", true),
    TOTAL_REPS("Total reps", false),
    MAX_REPS("Best set reps", false),
}

data class SessionPoint(
    val date: LocalDate,
    val activityId: Long,
    val value: Double,
    /** The set that produced the value (null for aggregate metrics). */
    val bestSet: StrengthSet?,
    val sets: List<StrengthSet>,
    /** True when the value beats every earlier session. */
    val isRecord: Boolean = false,
)

data class ExerciseSummary(
    /** Series id: an exercise key or a group id (see UserData). */
    val id: String,
    val sessions: Int,
    val totalSets: Int,
    val lastDate: LocalDate,
    val bestE1rmKg: Double?,
    val usesWeight: Boolean,
)

data class AnalysisSettings(
    val formula: E1rmFormula = E1rmFormula.EPLEY,
    /** Sets above this rep count are too unreliable to estimate a 1RM from. */
    val maxRepsForE1rm: Int = 12,
)

object Progression {

    private fun Workout.localDate(): LocalDate? = runCatching { LocalDate.parse(date) }.getOrNull()

    private fun StrengthSet.loaded(): Boolean = (weightKg ?: 0.0) > 0.0 && (reps ?: 0) > 0

    fun e1rm(set: StrengthSet, settings: AnalysisSettings): Double? {
        val reps = set.reps ?: return null
        val w = set.weightKg ?: return null
        if (w <= 0.0 || reps < 1 || reps > settings.maxRepsForE1rm) return null
        return settings.formula.estimate(w, reps)
    }

    /** One summary per series; [seriesOf] maps an exercise key to its series (identity without groups). */
    fun summarize(
        workouts: List<Workout>,
        settings: AnalysisSettings,
        seriesOf: (String) -> String = { it },
    ): List<ExerciseSummary> {
        data class Acc(var sessions: Int = 0, var sets: Int = 0, var last: LocalDate = LocalDate.MIN, var best: Double? = null, var weighted: Boolean = false)
        val map = HashMap<String, Acc>()
        for (w in workouts) {
            val d = w.localDate() ?: continue
            for ((key, sets) in w.sets.groupBy { seriesOf(it.exerciseKey) }) {
                val a = map.getOrPut(key) { Acc() }
                a.sessions++
                a.sets += sets.size
                if (d > a.last) a.last = d
                for (s in sets) {
                    if (s.loaded()) a.weighted = true
                    val e = e1rm(s, settings) ?: continue
                    if (a.best == null || e > a.best!!) a.best = e
                }
            }
        }
        return map.map { (k, a) -> ExerciseSummary(k, a.sessions, a.sets, a.last, a.best, a.weighted) }
            .sortedByDescending { it.lastDate }
    }

    /** One point per session in which any of [exerciseKeys] was performed, oldest first. */
    fun progression(
        workouts: List<Workout>,
        exerciseKeys: Set<String>,
        metric: Metric,
        settings: AnalysisSettings,
    ): List<SessionPoint> {
        val points = mutableListOf<SessionPoint>()
        for (w in workouts) {
            val d = w.localDate() ?: continue
            val sets = w.sets.filter { it.exerciseKey in exerciseKeys }
            if (sets.isEmpty()) continue
            val point: Pair<Double, StrengthSet?>? = when (metric) {
                Metric.E1RM -> sets.mapNotNull { s -> e1rm(s, settings)?.let { it to s } }.maxByOrNull { it.first }
                Metric.MAX_WEIGHT -> sets.filter { it.loaded() }
                    .maxWithOrNull(compareBy({ it.weightKg!! }, { it.reps!! }))
                    ?.let { it.weightKg!! to it }
                Metric.VOLUME -> sets.filter { it.loaded() }
                    .sumOf { it.weightKg!! * it.reps!! }
                    .takeIf { it > 0 }?.let { it to null }
                Metric.TOTAL_REPS -> sets.sumOf { it.reps ?: 0 }.takeIf { it > 0 }?.let { it.toDouble() to null }
                Metric.MAX_REPS -> sets.filter { (it.reps ?: 0) > 0 }.maxByOrNull { it.reps!! }?.let { it.reps!!.toDouble() to it }
            }
            if (point != null) points += SessionPoint(d, w.activityId, point.first, point.second, sets)
        }
        points.sortWith(compareBy({ it.date }, { it.activityId }))
        var best = Double.NEGATIVE_INFINITY
        return points.map { p ->
            val record = p.value > best + 1e-9
            if (record) best = p.value
            p.copy(isRecord = record)
        }
    }

    /** Trailing moving average over [window] sessions, aligned with [points]. */
    fun movingAverage(points: List<SessionPoint>, window: Int): List<Double> =
        points.indices.map { i ->
            val from = (i - window + 1).coerceAtLeast(0)
            points.subList(from, i + 1).map { it.value }.average()
        }
}
