package io.github.mathiashuber.gymsuite.analysis

import io.github.mathiashuber.gymsuite.data.StrengthSet
import io.github.mathiashuber.gymsuite.data.UserData
import io.github.mathiashuber.gymsuite.data.Workout
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ln
import kotlin.math.roundToInt

/** Body region a movement mostly trains. */
enum class Region { PUSH, PULL, LEGS, CORE, CONDITIONING }

/** What a single workout was, judged by its mix of regions. Order is the chart/legend order. */
enum class SessionType(val label: String, val short: String) {
    PUSH("Push", "Push"),
    PULL("Pull", "Pull"),
    LEGS("Legs", "Legs"),
    UPPER("Upper body", "Upper"),
    FULL_BODY("Full body", "Full"),
    CONDITIONING("Core & conditioning", "Cond."),
    UNCLASSIFIED("Unclassified", "?"),
}

object Movement {
    // Matched against the Garmin exercise name first, then the category, because
    // Garmin's categories are unreliable (e.g. "Suspended Row" sits under LATERAL_RAISE).
    // The longest matching keyword wins, so LEG_CURL beats CURL.
    private val rules: List<Pair<String, Region?>> = listOf(
        "PULL_UP" to Region.PULL, "CHIN_UP" to Region.PULL, "PULLDOWN" to Region.PULL, "ROW" to Region.PULL,
        "CURL" to Region.PULL, "FACE_PULL" to Region.PULL, "SHRUG" to Region.PULL, "HANG" to Region.PULL,
        "PULLOVER" to Region.PULL, "MUSCLE_UP" to Region.PULL,
        "LEG_CURL" to Region.LEGS, "DIP" to Region.PUSH, "PUSH_UP" to Region.PUSH, "PRESS" to Region.PUSH,
        "FLYE" to Region.PUSH, "FLY" to Region.PUSH, "TRICEPS" to Region.PUSH, "LATERAL_RAISE" to Region.PUSH,
        "FRONT_RAISE" to Region.PUSH, "HANDSTAND" to Region.PUSH,
        "SQUAT" to Region.LEGS, "LUNGE" to Region.LEGS, "DEADLIFT" to Region.LEGS, "HIP_RAISE" to Region.LEGS,
        "HIP_THRUST" to Region.LEGS, "CALF" to Region.LEGS, "STEP_UP" to Region.LEGS, "LEG_PRESS" to Region.LEGS,
        "LEG_EXTENSION" to Region.LEGS, "HIP_STABILITY" to Region.LEGS, "GLUTE" to Region.LEGS, "GOOD_MORNING" to Region.LEGS,
        "CRUNCH" to Region.CORE, "SIT_UP" to Region.CORE, "PLANK" to Region.CORE, "LEG_RAISE" to Region.CORE,
        "CORE" to Region.CORE, "AB_" to Region.CORE, "V_UP" to Region.CORE, "HOLLOW" to Region.CORE,
        "BURPEE" to Region.CONDITIONING, "JUMP" to Region.CONDITIONING, "CARDIO" to Region.CONDITIONING,
        "PLYO" to Region.CONDITIONING, "TOTAL_BODY" to Region.CONDITIONING, "CARRY" to Region.CONDITIONING,
        "OLYMPIC" to Region.CONDITIONING, "RUN" to Region.CONDITIONING,
        "WARM_UP" to null, "STRETCH" to null, "UNKNOWN" to null, "MOBILITY" to null,
    )

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val none = Any()

    fun region(category: String, name: String?): Region? {
        val cached = cache.getOrPut("$category/$name") {
            var found: Any = none
            for (token in listOfNotNull(name, category)) {
                val hit = rules.filter { token.contains(it.first) }.maxByOrNull { it.first.length } ?: continue
                found = hit.second ?: none
                break
            }
            found
        }
        return cached as? Region
    }

    private val bodyweight = listOf(
        "PUSH_UP", "PULL_UP", "CHIN_UP", "DIP", "SUSPENDED", "RING", "INVERTED", "AIR_SQUAT", "BURPEE", "PLANK",
        "JUMP", "SIT_UP", "CRUNCH", "LEG_RAISE", "PISTOL", "HANG", "MUSCLE_UP", "L_SIT", "HANDSTAND", "BODY_WEIGHT",
    )

    /** Bodyweight by exercise, not by logged weight: weights are often simply not logged. */
    fun isBodyweight(set: StrengthSet): Boolean =
        (set.weightKg ?: 0.0) <= 0.0 && bodyweight.any { (set.name ?: set.category).contains(it) }
}

data class ClassifiedWorkout(
    val workout: Workout,
    val date: LocalDate,
    val type: SessionType,
    /** Sets whose movement could be identified. */
    val identifiedSets: Int,
    val regions: Map<Region, Int>,
    val bodyweightSets: Int,
)

data class TemplateExercise(
    val seriesId: String,
    /** Share of this template's sessions that included the exercise. */
    val frequency: Double,
    val sets: Int,
    val reps: Int?,
    val weightKg: Double?,
)

/** The typical session of one type within an era: the reconstructed "day" of the plan. */
data class SessionTemplate(
    val type: SessionType,
    val sessions: Int,
    val names: List<String>,
    val exercises: List<TemplateExercise>,
)

data class EraHighlight(val seriesId: String, val metric: Metric, val from: Double, val to: Double, val sessions: Int) {
    val change: Double get() = if (from > 0) to / from - 1 else 0.0
}

data class Era(
    /** Activity id of the first workout; user names and merges refer to it. */
    val key: Long,
    val start: LocalDate,
    val end: LocalDate,
    val workouts: List<ClassifiedWorkout>,
    val sporadic: Boolean,
    val name: String,
    val autoName: String,
    val split: String,
    val style: String,
    val scheme: String?,
    val typeMix: Map<SessionType, Int>,
    val perWeek: Double,
    val bodyweightShare: Double?,
    val medianReps: Int?,
    val medianSetsPerExercise: Int?,
    val workoutNames: List<String>,
    val rotation: List<SessionType>?,
    /** Sessions per weekday, Monday first. */
    val weekdays: IntArray,
    val templates: List<SessionTemplate>,
    val highlights: List<EraHighlight>,
) {
    val dominantType: SessionType
        get() = typeMix.filterKeys { it != SessionType.UNCLASSIFIED }.maxByOrNull { it.value }?.key ?: SessionType.UNCLASSIFIED
}

data class EraResult(val eras: List<Era>, val classified: List<ClassifiedWorkout>) {
    fun eraOf(key: Long) = eras.firstOrNull { it.key == key }
    val current: Era? get() = eras.lastOrNull { !it.sporadic }
}

object Eras {
    /** A pause this long always ends an era. */
    const val BREAK_DAYS = 42
    private const val MIN_ERA_WORKOUTS = 5
    private const val SPORADIC_BELOW = 4
    /** Session type weighs like this many sets in the era cost. */
    private const val TYPE_WEIGHT = 6
    /** Segment penalty per detail level 1 (fewest eras) .. 5 (most eras). */
    private val penalties = doubleArrayOf(300.0, 200.0, 150.0, 100.0, 60.0)

    private val defaultNames = setOf(
        "strength", "strength training", "krafttraining", "musculation", "fuerza", "entrenamiento de fuerza",
        "forza", "allenamento di forza", "krachttraining", "styrketräning", "treino de força",
    )

    private fun clean(name: String) = name.replace("​", "").trim()

    fun classify(w: Workout): ClassifiedWorkout {
        val regions = HashMap<Region, Int>()
        var bw = 0
        for (s in w.sets) {
            val r = Movement.region(s.category, s.name) ?: continue
            regions.merge(r, 1, Int::plus)
            if (Movement.isBodyweight(s)) bw++
        }
        val identified = regions.values.sum()
        return ClassifiedWorkout(
            workout = w,
            date = runCatching { LocalDate.parse(w.date) }.getOrDefault(LocalDate.of(1970, 1, 1)),
            type = typeOf(regions, identified, w.sets.size),
            identifiedSets = identified,
            regions = regions,
            bodyweightSets = bw,
        )
    }

    fun typeOf(regions: Map<Region, Int>, identified: Int, total: Int): SessionType {
        if (identified < 3 || identified < 0.35 * total) return SessionType.UNCLASSIFIED
        val push = regions[Region.PUSH] ?: 0
        val pull = regions[Region.PULL] ?: 0
        val legs = regions[Region.LEGS] ?: 0
        val strength = push + pull + legs
        if (strength < 0.4 * identified) return SessionType.CONDITIONING
        val p = push.toDouble() / strength
        val q = pull.toDouble() / strength
        val l = legs.toDouble() / strength
        return when {
            p >= 0.65 -> SessionType.PUSH
            q >= 0.65 -> SessionType.PULL
            l >= 0.65 -> SessionType.LEGS
            l < 0.2 -> SessionType.UPPER
            p + q < 0.2 -> SessionType.LEGS
            else -> SessionType.FULL_BODY
        }
    }

    /**
     * Activity names that say nothing about the session: known defaults like
     * "Krafttraining", plus a very common name that doesn't predict the
     * session type (a real name like "leg day" does).
     */
    fun defaultNames(classified: List<ClassifiedWorkout>): Set<String> {
        val byName = classified.groupBy { clean(it.workout.name) }
        val top = byName.maxByOrNull { it.value.size }
        val dominant = top?.takeIf { (name, ws) ->
            val typed = ws.filter { it.type != SessionType.UNCLASSIFIED }
            val topTypeShare = typed.groupingBy { it.type }.eachCount().values.maxOrNull()?.toDouble()?.div(typed.size) ?: 1.0
            ws.size >= 0.3 * classified.size && ws.size >= 10 && topTypeShare < 0.6 && name.isNotEmpty()
        }?.key
        return listOfNotNull(dominant).toSet() + byName.keys.filter { it.lowercase() in defaultNames || it.isEmpty() }
    }

    fun detect(allWorkouts: List<Workout>, userData: UserData, analysis: AnalysisSettings = AnalysisSettings()): EraResult {
        val workouts = userData.applyExclusions(allWorkouts)
            .filter { it.sets.isNotEmpty() }
            .sortedBy { it.startTimeLocal }
        if (workouts.isEmpty()) return EraResult(emptyList(), emptyList())

        val classified = workouts.map { classify(it) }.toMutableList()
        val defaults = defaultNames(classified)
        // Unclassified sessions inherit the type of other sessions with the same custom name.
        val byName = HashMap<String, MutableMap<SessionType, Int>>()
        for (c in classified) {
            val n = clean(c.workout.name)
            if (c.type != SessionType.UNCLASSIFIED && n !in defaults) byName.getOrPut(n) { HashMap() }.merge(c.type, 1, Int::plus)
        }
        for (i in classified.indices) {
            val c = classified[i]
            if (c.type != SessionType.UNCLASSIFIED) continue
            byName[clean(c.workout.name)]?.maxByOrNull { it.value }?.let { classified[i] = c.copy(type = it.key) }
        }

        val bounds = segment(classified, userData)
        val eras = bounds.map { (from, to) -> describe(classified.subList(from, to), userData, defaults, analysis) }
        return EraResult(eras, classified)
    }

    /** Optimal partition of the timeline; returns [from, to) index ranges. */
    private fun segment(cs: List<ClassifiedWorkout>, userData: UserData): List<Pair<Int, Int>> {
        val n = cs.size
        // Tokens: what was trained (series, or category when ungrouped) plus the session type.
        val vocab = HashMap<String, Int>()
        val tokens: List<Map<Int, Int>> = cs.map { c ->
            val t = HashMap<Int, Int>()
            for (s in c.workout.sets) {
                if (Movement.region(s.category, s.name) == null) continue
                val g = userData.groupOfKey(s.exerciseKey)?.id ?: s.category
                t.merge(vocab.getOrPut(g) { vocab.size }, 1, Int::plus)
            }
            t.merge(vocab.getOrPut("type:" + c.type.name) { vocab.size }, TYPE_WEIGHT, Int::plus)
            t
        }
        val forced = BooleanArray(n + 1)
        val splits = userData.eraSplits
        for (i in 1 until n) {
            if (ChronoUnit.DAYS.between(cs[i - 1].date, cs[i].date) >= BREAK_DAYS) forced[i] = true
            if (cs[i].workout.activityId in splits) forced[i] = true
        }
        val penalty = penalties[(userData.eraDetail - 1).coerceIn(0, penalties.lastIndex)]

        val best = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }.also { it[0] = 0.0 }
        val prev = IntArray(n + 1)
        val counts = IntArray(vocab.size)
        for (j in 1..n) {
            counts.fill(0)
            var total = 0
            var sumVLogV = 0.0
            var i = j - 1
            while (i >= 0) {
                for ((tok, v) in tokens[i]) {
                    val old = counts[tok]
                    if (old > 0) sumVLogV -= old * ln(old.toDouble())
                    counts[tok] = old + v
                    sumVLogV += (old + v) * ln((old + v).toDouble())
                    total += v
                }
                // Multinomial negative log-likelihood of the segment's token mix.
                val cost = if (total > 0) total * ln(total.toDouble()) - sumVLogV else 0.0
                val size = j - i
                val bounded = (i == 0 || forced[i]) && (j == n || forced[j])
                if (size >= MIN_ERA_WORKOUTS || bounded) {
                    val c = best[i] + cost + penalty
                    if (c < best[j]) {
                        best[j] = c
                        prev[j] = i
                    }
                }
                if (forced[i]) break
                i--
            }
        }
        val out = ArrayList<Pair<Int, Int>>()
        var j = n
        while (j > 0) {
            out += prev[j] to j
            j = prev[j]
        }
        out.reverse()
        // User merges: drop the boundary in front of an era the user merged into its predecessor.
        val merged = ArrayList<Pair<Int, Int>>()
        for (seg in out) {
            val last = merged.lastOrNull()
            if (last != null && cs[seg.first].workout.activityId in userData.eraMerges) {
                merged[merged.lastIndex] = last.first to seg.second
            } else {
                merged += seg
            }
        }
        return merged
    }

    private fun <T : Comparable<T>> List<T>.median(): T? = if (isEmpty()) null else sorted()[size / 2]

    private fun describe(cs: List<ClassifiedWorkout>, userData: UserData, defaults: Set<String>, analysis: AnalysisSettings): Era {
        val start = cs.first().date
        val end = cs.last().date
        val typeMix = cs.groupingBy { it.type }.eachCount()
        val weeks = (ChronoUnit.DAYS.between(start, end) + 7) / 7.0
        val identified = cs.sumOf { it.identifiedSets }
        val bwShare = if (identified > 0) cs.sumOf { it.bodyweightSets }.toDouble() / identified else null
        val workingSets = cs.flatMap { c -> c.workout.sets.filter { Movement.region(it.category, it.name) != null } }
        val reps = workingSets.mapNotNull { it.reps }.filter { it > 0 }
        val setsPerExercise = cs.flatMap { c ->
            c.workout.sets.filter { Movement.region(it.category, it.name) != null }.groupingBy { it.exerciseKey }.eachCount().values
        }
        val medianReps = reps.median()
        val medianSets = setsPerExercise.median()
        val fives = if (reps.isEmpty()) 0.0 else reps.count { it == 5 }.toDouble() / reps.size
        val scheme = if (fives >= 0.4 && (medianSets ?: 0) >= 5) "5×5" else null

        val names = cs.map { clean(it.workout.name) }.filter { it !in defaults && it.isNotBlank() }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

        val sporadic = cs.size < SPORADIC_BELOW
        val split = splitLabel(typeMix)
        val style = when {
            bwShare == null -> "Mixed"
            bwShare >= 0.7 -> "Calisthenics"
            bwShare <= 0.3 -> "Weights"
            else -> "Hybrid"
        }
        val autoName = when {
            sporadic -> "Sporadic sessions"
            scheme != null -> "$scheme $split"
            else -> "$style · $split"
        }
        val weekdays = IntArray(7)
        cs.forEach { weekdays[it.date.dayOfWeek.value - DayOfWeek.MONDAY.value]++ }

        return Era(
            key = cs.first().workout.activityId,
            start = start,
            end = end,
            workouts = cs,
            sporadic = sporadic,
            name = userData.eraNames[cs.first().workout.activityId.toString()] ?: autoName,
            autoName = autoName,
            split = split,
            style = style,
            scheme = scheme,
            typeMix = typeMix,
            perWeek = cs.size / weeks,
            bodyweightShare = bwShare,
            medianReps = medianReps,
            medianSetsPerExercise = medianSets,
            workoutNames = names,
            rotation = rotation(cs.map { it.type }),
            weekdays = weekdays,
            templates = templates(cs, userData, defaults),
            highlights = highlights(cs, userData, analysis),
        )
    }

    fun splitLabel(mix: Map<SessionType, Int>): String {
        val known = mix.filterKeys { it != SessionType.UNCLASSIFIED }
        val n = known.values.sum().toDouble()
        if (n == 0.0) return "Unknown split"
        fun s(t: SessionType) = (known[t] ?: 0) / n
        val push = s(SessionType.PUSH)
        val pull = s(SessionType.PULL)
        val legs = s(SessionType.LEGS)
        val upper = s(SessionType.UPPER)
        val full = s(SessionType.FULL_BODY)
        return when {
            push >= 0.15 && pull >= 0.15 && legs >= 0.12 -> "Push/Pull/Legs"
            push + pull >= 0.6 && push >= 0.2 && pull >= 0.2 -> "Push/Pull"
            full >= 0.5 -> "Full body"
            upper >= 0.25 && legs >= 0.2 -> "Upper/Lower"
            upper + push + pull >= 0.7 -> "Upper body"
            legs >= 0.5 -> "Leg focus"
            s(SessionType.CONDITIONING) >= 0.5 -> "Conditioning"
            else -> "Mixed"
        }
    }

    /** The repeating order of session types, if there is a clear one (e.g. Push → Pull → Legs). */
    fun rotation(types: List<SessionType>): List<SessionType>? {
        val seq = types.filter { it != SessionType.UNCLASSIFIED }
        if (seq.size < 4) return null
        val counts = seq.groupingBy { it }.eachCount()
        val main = counts.filter { it.value >= seq.size * 0.12 }.keys
        if (main.size < 2) return null
        val trans = HashMap<Pair<SessionType, SessionType>, Int>()
        seq.zipWithNext().filter { (a, b) -> a != b && a in main && b in main }.forEach { trans.merge(it, 1, Int::plus) }
        val totalTrans = trans.values.sum()
        if (totalTrans == 0) return null
        val chain = mutableListOf(main.maxBy { counts[it] ?: 0 })
        while (true) {
            val cur = chain.last()
            val next = main.filter { it !in chain }.maxByOrNull { trans[cur to it] ?: 0 } ?: break
            if ((trans[cur to next] ?: 0) == 0) break
            chain += next
        }
        if (chain.size < 2) return null
        val covered = chain.zipWithNext().sumOf { trans[it] ?: 0 } + (trans[chain.last() to chain.first()] ?: 0)
        if (covered < 0.45 * totalTrans) return null
        // Start the cycle at the canonical first type so PPL reads Push → Pull → Legs.
        val first = chain.minBy { it.ordinal }
        val k = chain.indexOf(first)
        return chain.drop(k) + chain.take(k)
    }

    private fun templates(cs: List<ClassifiedWorkout>, userData: UserData, defaults: Set<String>): List<SessionTemplate> =
        cs.filter { it.type != SessionType.UNCLASSIFIED }.groupBy { it.type }.entries
            .filter { it.value.size >= 2 }
            .sortedByDescending { it.value.size }
            .map { (type, sessions) ->
                data class Acc(var sessions: Int = 0, val sets: MutableList<Int> = ArrayList(), val reps: MutableList<Int> = ArrayList(), val weights: MutableList<Double> = ArrayList(), val positions: MutableList<Double> = ArrayList())
                val acc = HashMap<String, Acc>()
                for (c in sessions) {
                    val working = c.workout.sets.filter { Movement.region(it.category, it.name) != null }
                    if (working.isEmpty()) continue
                    for ((series, sets) in working.groupBy { userData.seriesOf(it.exerciseKey) }) {
                        val a = acc.getOrPut(series) { Acc() }
                        a.sessions++
                        a.sets += sets.size
                        a.reps += sets.mapNotNull { it.reps }.filter { it > 0 }
                        a.weights += sets.mapNotNull { it.weightKg }.filter { it > 0 }
                        a.positions += working.indexOf(sets.first()).toDouble() / working.size
                    }
                }
                val exercises = acc.entries
                    .filter { it.value.sessions >= 2 && it.value.sessions >= 0.35 * sessions.size }
                    .sortedBy { it.value.positions.median() }
                    .take(8)
                    .map { (series, a) ->
                        TemplateExercise(
                            seriesId = series,
                            frequency = a.sessions.toDouble() / sessions.size,
                            sets = (a.sets.sum().toDouble() / a.sessions).roundToInt().coerceAtLeast(1),
                            reps = a.reps.median(),
                            weightKg = a.weights.median(),
                        )
                    }
                val names = sessions.map { clean(it.workout.name) }.filter { it !in defaults && it.isNotBlank() }
                    .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(3)
                SessionTemplate(type, sessions.size, names, exercises)
            }

    private val strengthRegions = setOf(Region.PUSH, Region.PULL, Region.LEGS)

    private fun highlights(cs: List<ClassifiedWorkout>, userData: UserData, analysis: AnalysisSettings): List<EraHighlight> {
        val workouts = cs.map { it.workout }
        val sessionsPerSeries = HashMap<String, Int>()
        for (w in workouts) {
            w.sets.filter { Movement.region(it.category, it.name) in strengthRegions }
                .map { userData.seriesOf(it.exerciseKey) }.toSet()
                .forEach { sessionsPerSeries.merge(it, 1, Int::plus) }
        }
        return sessionsPerSeries.entries.filter { it.value >= 4 }.sortedByDescending { it.value }.take(5).mapNotNull { (series, count) ->
            val members = userData.membersOf(series)
            val loaded = workouts.any { w -> w.sets.any { it.exerciseKey in members && (it.weightKg ?: 0.0) > 0 } }
            val metric = if (loaded) Metric.E1RM else Metric.MAX_REPS
            val pts = Progression.progression(workouts, members, metric, analysis)
            if (pts.size < 3) return@mapNotNull null
            // Average a few sessions at each end so one odd day doesn't decide the trend.
            val k = (pts.size / 3).coerceIn(1, 3)
            EraHighlight(series, metric, pts.take(k).map { it.value }.average(), pts.takeLast(k).map { it.value }.average(), count)
        }
    }
}
