package io.github.mathiashuber.gymsuite

import io.github.mathiashuber.gymsuite.analysis.Eras
import io.github.mathiashuber.gymsuite.analysis.Movement
import io.github.mathiashuber.gymsuite.analysis.Region
import io.github.mathiashuber.gymsuite.analysis.SessionType
import io.github.mathiashuber.gymsuite.data.StrengthSet
import io.github.mathiashuber.gymsuite.data.UserData
import io.github.mathiashuber.gymsuite.data.Workout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ErasTest {

    private var nextId = 1L

    private fun workout(date: LocalDate, name: String, vararg exercises: Triple<String, String?, Int>, sets: Int = 3, kg: Double? = null): Workout {
        val list = ArrayList<StrengthSet>()
        for ((cat, ex, reps) in exercises) repeat(sets) { list += StrengthSet(list.size, cat, ex, reps, kg, null, null) }
        return Workout(nextId++, name, "$date 18:00:00", null, list)
    }

    private val push = arrayOf(Triple("PUSH_UP", "PUSH_UP", 12), Triple("TRICEPS_EXTENSION", "SUSPENDED_DIP", 8), Triple("SHOULDER_PRESS", "ARNOLD_PRESS", 10))
    private val pull = arrayOf(Triple("PULL_UP", "PULL_UP", 6), Triple("LATERAL_RAISE", "SUSPENDED_ROW", 10), Triple("CURL", "DUMBBELL_BICEPS_CURL", 12))
    private val legs = arrayOf(Triple("SQUAT", "AIR_SQUAT", 15), Triple("LUNGE", "LUNGE", 12), Triple("SQUAT", "JUMP_SQUAT", 10))
    private val fiveByFive = arrayOf(Triple("SQUAT", "BARBELL_BACK_SQUAT", 5), Triple("BENCH_PRESS", "BARBELL_BENCH_PRESS", 5), Triple("ROW", "BARBELL_ROW", 5))

    /** 8 weeks of push/pull/legs, then 8 weeks of 5x5 full body, 3 sessions a week. */
    private fun history(gapDays: Long = 0): List<Workout> {
        val out = ArrayList<Workout>()
        var d = LocalDate.of(2022, 1, 3)
        repeat(24) { i ->
            out += when (i % 3) {
                0 -> workout(d, "Strength", *push)
                1 -> workout(d, "Strength", *pull)
                else -> workout(d, "Strength", *legs)
            }
            d = d.plusDays(if (i % 3 == 2) 3 else 2)
        }
        d = d.plusDays(gapDays)
        repeat(24) {
            out += workout(d, "5x5", *fiveByFive, sets = 5, kg = 80.0)
            d = d.plusDays(if (it % 3 == 2) 3 else 2)
        }
        return out
    }

    @Test
    fun movementsUseNameBeforeCategory() {
        assertEquals(Region.PULL, Movement.region("LATERAL_RAISE", "SUSPENDED_ROW"))
        assertEquals(Region.PUSH, Movement.region("LATERAL_RAISE", "RING_DIP"))
        assertEquals(Region.LEGS, Movement.region("LEG_CURL", "LYING_LEG_CURL"))
        assertEquals(Region.PUSH, Movement.region("LATERAL_RAISE", null))
        assertNull(Movement.region("UNKNOWN", null))
        assertNull(Movement.region("WARM_UP", "WARM_UP"))
    }

    @Test
    fun sessionTypes() {
        assertEquals(SessionType.PUSH, Eras.classify(workout(LocalDate.now(), "x", *push)).type)
        assertEquals(SessionType.PULL, Eras.classify(workout(LocalDate.now(), "x", *pull)).type)
        assertEquals(SessionType.LEGS, Eras.classify(workout(LocalDate.now(), "x", *legs)).type)
        assertEquals(SessionType.FULL_BODY, Eras.classify(workout(LocalDate.now(), "x", *fiveByFive)).type)
        assertEquals(SessionType.UPPER, Eras.classify(workout(LocalDate.now(), "x", push[0], pull[0])).type)
        assertEquals(SessionType.UNCLASSIFIED, Eras.classify(workout(LocalDate.now(), "x", Triple("UNKNOWN", null, 10))).type)
    }

    @Test
    fun detectsTwoErasWithTheirPlans() {
        val res = Eras.detect(history(), UserData())
        assertEquals(2, res.eras.size)
        val (ppl, strength) = res.eras
        assertEquals(24, ppl.workouts.size)
        assertEquals("Push/Pull/Legs", ppl.split)
        assertEquals(listOf(SessionType.PUSH, SessionType.PULL, SessionType.LEGS), ppl.rotation)
        assertEquals(3, ppl.templates.size)
        assertTrue(ppl.templates.first { it.type == SessionType.PULL }.exercises.any { it.seriesId == "LATERAL_RAISE/SUSPENDED_ROW" })
        assertEquals("5×5 Full body", strength.name)
        assertEquals(listOf("5x5"), strength.workoutNames)
        val squat = strength.templates.single().exercises.first { it.seriesId == "SQUAT/BARBELL_BACK_SQUAT" }
        assertEquals(5, squat.sets)
        assertEquals(5, squat.reps)
        assertEquals(80.0, squat.weightKg!!, 1e-9)
        assertEquals(strength.key, res.current?.key)
    }

    @Test
    fun longBreakAlwaysSplits() {
        // Same training before and after a two-month pause is still two eras.
        val w = history().take(24)
        val later = w.map { it.copy(activityId = it.activityId + 1000, startTimeLocal = LocalDate.parse(it.date).plusDays(120).toString() + " 18:00:00") }
        assertEquals(2, Eras.detect(w + later, UserData()).eras.size)
    }

    @Test
    fun userSplitsMergesAndNames() {
        val h = history()
        val auto = Eras.detect(h, UserData())
        val second = auto.eras[1].key
        var ud = UserData().mergeEraIntoPrevious(second)
        assertEquals(1, Eras.detect(h, ud).eras.size)

        ud = UserData().startEraAt(h[12].activityId).renameEra(h[0].activityId, "Garage PPL")
        val split = Eras.detect(h, ud)
        assertEquals(3, split.eras.size)
        assertEquals(12, split.eras[0].workouts.size)
        assertEquals("Garage PPL", split.eras[0].name)
        assertEquals(h[12].activityId, split.eras[1].key)
    }

    @Test
    fun unclassifiedSessionsInheritTypeFromTheirName() {
        val d = LocalDate.of(2023, 1, 2)
        val ws = listOf(
            workout(d, "leg day", *legs),
            workout(d.plusDays(2), "leg day", Triple("UNKNOWN", null, 10)),
        )
        val types = Eras.detect(ws, UserData()).classified.map { it.type }
        assertEquals(listOf(SessionType.LEGS, SessionType.LEGS), types)
    }

    @Test
    fun rotationNeedsARepeatingOrder() {
        val ppl = List(4) { listOf(SessionType.PUSH, SessionType.PULL, SessionType.LEGS) }.flatten()
        assertEquals(listOf(SessionType.PUSH, SessionType.PULL, SessionType.LEGS), Eras.rotation(ppl))
        assertNull(Eras.rotation(List(8) { SessionType.FULL_BODY }))
    }
}
