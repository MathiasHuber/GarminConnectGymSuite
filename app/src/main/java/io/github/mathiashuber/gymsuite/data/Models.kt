package io.github.mathiashuber.gymsuite.data

import kotlinx.serialization.Serializable

/** One working set of a strength activity, normalised from Garmin's exerciseSets. */
@Serializable
data class StrengthSet(
    /** Position of the set within the activity (rest sets excluded). */
    val index: Int,
    /** Garmin exercise category, e.g. BENCH_PRESS. "UNKNOWN" when not identified. */
    val category: String,
    /** Garmin exercise name, e.g. BARBELL_BENCH_PRESS. Null when only the category is known. */
    val name: String?,
    val reps: Int?,
    /** Load in kilograms; null or 0 for bodyweight sets. */
    val weightKg: Double?,
    val durationSec: Double?,
    val startTime: String?,
) {
    val exerciseKey: String get() = exerciseKey(category, name)
}

@Serializable
data class Workout(
    val activityId: Long,
    val name: String,
    /** Garmin's local start time, "yyyy-MM-dd HH:mm:ss". */
    val startTimeLocal: String,
    val durationSec: Double? = null,
    val sets: List<StrengthSet>,
) {
    val date: String get() = startTimeLocal.take(10)
}

@Serializable
data class StoreFile(
    val version: Int = 1,
    val lastSyncEpochMs: Long? = null,
    val workouts: List<Workout> = emptyList(),
)

fun exerciseKey(category: String, name: String?): String =
    if (name.isNullOrBlank()) category else "$category/$name"

/** "BARBELL_BENCH_PRESS" -> "Barbell Bench Press". */
fun humanize(enumName: String): String =
    enumName.split('_').filter { it.isNotBlank() }.joinToString(" ") { word ->
        word.lowercase().replaceFirstChar { it.uppercase() }
    }

fun exerciseDisplayName(key: String): String {
    val category = key.substringBefore('/')
    val name = key.substringAfter('/', "")
    return when {
        name.isNotEmpty() -> humanize(name)
        category == "UNKNOWN" -> "Unidentified exercise"
        else -> humanize(category)
    }
}
