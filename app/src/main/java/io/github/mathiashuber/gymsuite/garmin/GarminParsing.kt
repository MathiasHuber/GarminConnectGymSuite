package io.github.mathiashuber.gymsuite.garmin

import io.github.mathiashuber.gymsuite.data.StrengthSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Summary of an activity from the activity list endpoint. */
data class ActivitySummary(
    val activityId: Long,
    val name: String,
    val startTimeLocal: String,
    val typeKey: String,
    val durationSec: Double?,
)

object GarminParsing {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.doubleOrNull

    fun parseActivityList(body: String): List<ActivitySummary> {
        val root = json.parseToJsonElement(body)
        // The search endpoint returns a bare array; some variants wrap it.
        val items: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["activityList"] ?: root["activities"])?.jsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["activityId"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val type = (o["activityType"] as? JsonObject)?.get("typeKey").str() ?: ""
            ActivitySummary(
                activityId = id,
                name = o["activityName"].str() ?: "Strength",
                startTimeLocal = o["startTimeLocal"].str() ?: o["startTimeGMT"].str() ?: "",
                typeKey = type,
                durationSec = o["duration"].num(),
            )
        }
    }

    /**
     * Parse /activity-service/activity/{id}/exerciseSets.
     *
     * Garmin reports set weight in grams; rest sets (setType REST) are dropped.
     * The exercise with the highest probability is taken as the set's exercise.
     */
    fun parseExerciseSets(body: String): List<StrengthSet> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val sets = root["exerciseSets"] as? JsonArray ?: return emptyList()
        val out = mutableListOf<StrengthSet>()
        for (el in sets) {
            val s = el as? JsonObject ?: continue
            if (s["setType"].str() == "REST") continue
            val exercise = (s["exercises"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.maxByOrNull { it["probability"].num() ?: 0.0 }
            val category = exercise?.get("category").str()?.takeIf { it.isNotBlank() } ?: "UNKNOWN"
            val name = exercise?.get("name").str()?.takeIf { it.isNotBlank() }
            val reps = s["repetitionCount"].num()?.toInt()
            val grams = s["weight"].num()
            out += StrengthSet(
                index = out.size,
                category = category,
                name = name,
                reps = reps,
                weightKg = grams?.let { it / 1000.0 },
                durationSec = s["duration"].num(),
                startTime = s["startTime"].str(),
            )
        }
        return out
    }

    fun jsonObjectOrNull(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
}
