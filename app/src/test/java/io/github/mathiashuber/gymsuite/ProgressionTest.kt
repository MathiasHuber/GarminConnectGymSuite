package io.github.mathiashuber.gymsuite

import io.github.mathiashuber.gymsuite.analysis.AnalysisSettings
import io.github.mathiashuber.gymsuite.analysis.E1rmFormula
import io.github.mathiashuber.gymsuite.analysis.Metric
import io.github.mathiashuber.gymsuite.analysis.Progression
import io.github.mathiashuber.gymsuite.data.Export
import io.github.mathiashuber.gymsuite.data.StrengthSet
import io.github.mathiashuber.gymsuite.data.Workout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressionTest {

    private fun set(i: Int, reps: Int, kg: Double, cat: String = "SQUAT", name: String? = "BARBELL_BACK_SQUAT") =
        StrengthSet(i, cat, name, reps, kg, null, null)

    private val key = "SQUAT/BARBELL_BACK_SQUAT"

    private val workouts = listOf(
        Workout(2, "B", "2024-01-08 18:00:00", null, listOf(set(0, 3, 110.0), set(1, 8, 90.0))),
        Workout(1, "A", "2024-01-01 18:00:00", null, listOf(set(0, 5, 100.0), set(1, 5, 100.0), set(2, 10, 20.0, "PULL_UP", null))),
        Workout(3, "C", "2024-01-15 18:00:00", null, listOf(set(0, 5, 100.0), set(1, 15, 80.0))),
    )

    @Test
    fun formulas() {
        assertEquals(100.0, E1rmFormula.EPLEY.estimate(100.0, 1), 1e-9)
        assertEquals(116.667, E1rmFormula.EPLEY.estimate(100.0, 5), 1e-3)
        assertEquals(112.5, E1rmFormula.BRZYCKI.estimate(100.0, 5), 1e-3)
        assertTrue(E1rmFormula.BRZYCKI.estimate(100.0, 40).isFinite())
    }

    @Test
    fun e1rmProgressionIsChronologicalWithRecords() {
        val pts = Progression.progression(workouts, setOf(key), Metric.E1RM, AnalysisSettings())
        assertEquals(listOf(1L, 2L, 3L), pts.map { it.activityId })
        // Session 2: 3x110 (121) beats 8x90 (114).
        assertEquals(121.0, pts[1].value, 1e-9)
        assertEquals(110.0, pts[1].bestSet!!.weightKg!!, 1e-9)
        assertEquals(listOf(true, true, false), pts.map { it.isRecord })
    }

    @Test
    fun highRepSetsAreExcludedFromE1rm() {
        val pts = Progression.progression(workouts, setOf(key), Metric.E1RM, AnalysisSettings(maxRepsForE1rm = 12))
        // 15x80 would be 120 with Epley; it must not win over 5x100.
        assertEquals(116.667, pts[2].value, 1e-3)
    }

    @Test
    fun otherMetrics() {
        val s = AnalysisSettings()
        assertEquals(listOf(100.0, 110.0, 100.0), Progression.progression(workouts, setOf(key), Metric.MAX_WEIGHT, s).map { it.value })
        assertEquals(1000.0, Progression.progression(workouts, setOf(key), Metric.VOLUME, s)[0].value, 1e-9)
        assertEquals(listOf(10.0, 11.0, 20.0), Progression.progression(workouts, setOf(key), Metric.TOTAL_REPS, s).map { it.value })
    }

    @Test
    fun summary() {
        val sums = Progression.summarize(workouts, AnalysisSettings())
        val squat = sums.first { it.id == key }
        assertEquals(3, squat.sessions)
        assertEquals(121.0, squat.bestE1rmKg!!, 1e-9)
        assertEquals("2024-01-15", squat.lastDate.toString())
    }

    @Test
    fun movingAverage() {
        val pts = Progression.progression(workouts, setOf(key), Metric.MAX_WEIGHT, AnalysisSettings())
        assertEquals(listOf(100.0, 105.0, 105.0), Progression.movingAverage(pts, 2))
    }

    @Test
    fun csvQuotesAndOrders() {
        val csv = Export.toCsv(listOf(Workout(9, "Legs, heavy", "2024-02-01 10:00:00", null, listOf(set(0, 5, 100.0)))))
        val lines = csv.trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("\"Legs, heavy\""))
        assertTrue(lines[1].contains("Barbell Back Squat"))
    }
}
