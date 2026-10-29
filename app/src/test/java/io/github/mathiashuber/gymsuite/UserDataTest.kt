package io.github.mathiashuber.gymsuite

import io.github.mathiashuber.gymsuite.analysis.AnalysisSettings
import io.github.mathiashuber.gymsuite.analysis.Metric
import io.github.mathiashuber.gymsuite.analysis.Progression
import io.github.mathiashuber.gymsuite.data.Export
import io.github.mathiashuber.gymsuite.data.StrengthSet
import io.github.mathiashuber.gymsuite.data.UserData
import io.github.mathiashuber.gymsuite.data.Workout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDataTest {

    private fun set(i: Int, reps: Int, kg: Double, cat: String, name: String?) = StrengthSet(i, cat, name, reps, kg, null, null)

    private val pushA = "PUSH_UP/PUSH_UP"
    private val pushB = "PUSH_UP"
    private val workouts = listOf(
        Workout(1, "A", "2023-01-01 10:00:00", null, listOf(set(0, 20, 0.0, "PUSH_UP", "PUSH_UP"))),
        Workout(2, "B", "2023-02-01 10:00:00", null, listOf(set(0, 70, 0.0, "PUSH_UP", null), set(1, 25, 0.0, "PUSH_UP", null))),
        Workout(3, "C", "2023-03-01 10:00:00", null, listOf(set(0, 30, 0.0, "PUSH_UP", "PUSH_UP"))),
    )

    @Test
    fun groupsMergeSeries() {
        val ud = UserData().createGroup("g1", "Push-ups", listOf(pushA, pushB))
        val id = UserData.GROUP_PREFIX + "g1"
        assertEquals(id, ud.seriesOf(pushA))
        assertEquals(id, ud.seriesOf(pushB))
        assertEquals("Push-ups", ud.displayName(id))

        val sums = Progression.summarize(workouts, AnalysisSettings(), ud::seriesOf)
        assertEquals(1, sums.size)
        assertEquals(3, sums[0].sessions)

        val pts = Progression.progression(workouts, ud.membersOf(id), Metric.MAX_REPS, AnalysisSettings())
        assertEquals(listOf(20.0, 70.0, 30.0), pts.map { it.value })
    }

    @Test
    fun excludingAnOutlierSet() {
        val ud = UserData().toggleSet(2, 0)
        val pts = Progression.progression(ud.applyExclusions(workouts), setOf(pushB), Metric.MAX_REPS, AnalysisSettings())
        assertEquals(listOf(25.0), pts.map { it.value })
        // Set indexes survive filtering, so the remaining set can still be referenced.
        assertEquals(1, pts[0].bestSet!!.index)
        assertTrue(ud.toggleSet(2, 0).excludedSets.isEmpty())
    }

    @Test
    fun excludingAWorkout() {
        val ud = UserData().toggleWorkout(2)
        assertEquals(listOf(1L, 3L), ud.applyExclusions(workouts).map { it.activityId })
        assertTrue(ud.isSetExcluded(2, workouts[1].sets[1]))
        assertFalse(ud.toggleWorkout(2).isWorkoutExcluded(2))
    }

    @Test
    fun favoritesFollowGroups() {
        var ud = UserData().toggleFavorite(pushA)
        ud = ud.createGroup("g1", "Push-ups", listOf(pushA, pushB))
        assertEquals(setOf(UserData.GROUP_PREFIX + "g1"), ud.favorites)
        // Merging an existing group into a new one replaces it.
        ud = ud.createGroup("g2", "All pushing", listOf(UserData.GROUP_PREFIX + "g1", "BENCH_PRESS/BARBELL_BENCH_PRESS"))
        assertEquals(listOf("g2"), ud.groups.map { it.id })
        assertEquals(3, ud.groups[0].members.size)
        assertEquals(setOf(UserData.GROUP_PREFIX + "g2"), ud.favorites)
    }

    @Test
    fun emptiedGroupsDisappear() {
        var ud = UserData().createGroup("g1", "Push-ups", listOf(pushA, pushB)).toggleFavorite(UserData.GROUP_PREFIX + "g1")
        ud = ud.removeFromGroup("g1", pushA).removeFromGroup("g1", pushB)
        assertTrue(ud.groups.isEmpty())
        assertTrue(ud.favorites.isEmpty())
        assertEquals(pushA, ud.seriesOf(pushA))
    }

    @Test
    fun addToGroupMovesMember() {
        var ud = UserData().createGroup("g1", "A", listOf(pushA, pushB)).createGroup("g2", "B", listOf("ROW/BARBELL_ROW"))
        ud = ud.addToGroup("g2", pushA)
        assertEquals(listOf(pushB), ud.groups.first { it.id == "g1" }.members)
        assertEquals(UserData.GROUP_PREFIX + "g2", ud.seriesOf(pushA))
        assertNull(ud.group(UserData.GROUP_PREFIX + "missing"))
    }

    @Test
    fun csvCarriesGroupAndExclusion() {
        val ud = UserData().createGroup("g1", "Push-ups", listOf(pushB)).toggleSet(2, 0)
        val lines = Export.toCsv(workouts, ud).trim().lines()
        assertTrue(lines[0].endsWith(",group,excluded"))
        assertTrue(lines[2].endsWith(",Push-ups,true"))
        assertTrue(lines[3].endsWith(",Push-ups,false"))
        assertTrue(lines[1].endsWith(",,false"))
    }
}
