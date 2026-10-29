package io.github.mathiashuber.gymsuite

import io.github.mathiashuber.gymsuite.data.exerciseDisplayName
import io.github.mathiashuber.gymsuite.garmin.GarminAuth
import io.github.mathiashuber.gymsuite.garmin.GarminParsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GarminParsingTest {

    @Test
    fun parsesExerciseSetsAndDropsRests() {
        val body = """
            {"activityId": 1, "exerciseSets": [
              {"exercises": [{"category": "BENCH_PRESS", "name": "BARBELL_BENCH_PRESS", "probability": 100.0}],
               "duration": 40.5, "repetitionCount": 5, "weight": 100000.0, "setType": "ACTIVE", "startTime": "2024-03-01T18:00:00.0"},
              {"exercises": [], "duration": 120.0, "repetitionCount": null, "weight": null, "setType": "REST"},
              {"exercises": [{"category": "PULL_UP", "name": null, "probability": 60.0},
                             {"category": "ROW", "name": "BARBELL_ROW", "probability": 30.0}],
               "repetitionCount": 8, "weight": 0.0, "setType": "ACTIVE"},
              {"exercises": [], "repetitionCount": 10, "weight": 20000.0, "setType": "ACTIVE"}
            ]}
        """.trimIndent()
        val sets = GarminParsing.parseExerciseSets(body)
        assertEquals(3, sets.size)
        assertEquals("BENCH_PRESS/BARBELL_BENCH_PRESS", sets[0].exerciseKey)
        assertEquals(100.0, sets[0].weightKg!!, 1e-9)
        assertEquals(5, sets[0].reps)
        assertEquals("PULL_UP", sets[1].exerciseKey)
        assertEquals(1, sets[1].index)
        assertEquals("UNKNOWN", sets[2].category)
    }

    @Test
    fun handlesMissingExerciseSets() {
        assertTrue(GarminParsing.parseExerciseSets("""{"activityId": 1, "exerciseSets": null}""").isEmpty())
    }

    @Test
    fun parsesActivityList() {
        val body = """[
            {"activityId": 42, "activityName": "Push day", "startTimeLocal": "2024-03-01 18:00:00",
             "activityType": {"typeKey": "strength_training"}, "duration": 3600.0},
            {"activityName": "no id"}
        ]"""
        val list = GarminParsing.parseActivityList(body)
        assertEquals(1, list.size)
        assertEquals(42L, list[0].activityId)
        assertEquals("strength_training", list[0].typeKey)
    }

    @Test
    fun displayNames() {
        assertEquals("Barbell Bench Press", exerciseDisplayName("BENCH_PRESS/BARBELL_BENCH_PRESS"))
        assertEquals("Pull Up", exerciseDisplayName("PULL_UP"))
        assertEquals("Unidentified exercise", exerciseDisplayName("UNKNOWN"))
    }

    @Test
    fun readsJwtClaims() {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"RS256"}""".toByteArray())
        val payload = enc.encodeToString("""{"client_id":"GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2","exp":1000000}""".toByteArray())
        val token = "$header.$payload.sig"
        assertEquals("GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2", GarminAuth.clientIdFromJwt(token))
        assertTrue(GarminAuth.expiresSoon(token, nowEpochSec = 999_500))
        assertFalse(GarminAuth.expiresSoon(token, nowEpochSec = 990_000))
        assertNull(GarminAuth.clientIdFromJwt("not-a-jwt"))
    }
}
