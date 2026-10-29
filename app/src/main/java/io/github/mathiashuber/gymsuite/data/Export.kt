package io.github.mathiashuber.gymsuite.data

/** Flat, analysis-friendly CSV: one row per working set. */
object Export {
    private val header = listOf(
        "date", "start_time_local", "activity_id", "activity_name", "set_index",
        "exercise_category", "exercise_name", "exercise", "reps", "weight_kg", "duration_s", "set_start_time",
    )

    private fun cell(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun toCsv(workouts: List<Workout>): String = buildString {
        appendLine(header.joinToString(","))
        for (w in workouts.sortedBy { it.startTimeLocal }) {
            for (s in w.sets) {
                appendLine(
                    listOf(
                        w.date, w.startTimeLocal, w.activityId, w.name, s.index,
                        s.category, s.name, exerciseDisplayName(s.exerciseKey), s.reps,
                        s.weightKg, s.durationSec, s.startTime,
                    ).joinToString(",") { cell(it) },
                )
            }
        }
    }
}
