package io.github.mathiashuber.gymsuite.data

import android.content.Context
import io.github.mathiashuber.gymsuite.analysis.AnalysisSettings
import io.github.mathiashuber.gymsuite.analysis.E1rmFormula
import io.github.mathiashuber.gymsuite.garmin.GarminClient
import io.github.mathiashuber.gymsuite.garmin.GarminParsing
import io.github.mathiashuber.gymsuite.garmin.GarminTokens
import io.github.mathiashuber.gymsuite.garmin.TokenStore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

class PrefsTokenStore(context: Context) : TokenStore {
    // App-private storage; nothing leaves the phone except requests to Garmin.
    private val prefs = context.getSharedPreferences("garmin_auth", Context.MODE_PRIVATE)

    override fun load(): GarminTokens? = prefs.getString("tokens", null)?.let {
        runCatching { GarminParsing.json.decodeFromString<GarminTokens>(it) }.getOrNull()
    }

    override fun save(tokens: GarminTokens?) {
        prefs.edit().apply {
            if (tokens == null) remove("tokens") else putString("tokens", GarminParsing.json.encodeToString(tokens))
        }.apply()
    }
}

enum class WeightUnit(val label: String, val perKg: Double) { KG("kg", 1.0), LB("lb", 2.2046226218) }

data class UserSettings(
    val unit: WeightUnit = WeightUnit.KG,
    val analysis: AnalysisSettings = AnalysisSettings(),
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = UserSettings(
        unit = runCatching { WeightUnit.valueOf(prefs.getString("unit", "KG")!!) }.getOrDefault(WeightUnit.KG),
        analysis = AnalysisSettings(
            formula = runCatching { E1rmFormula.valueOf(prefs.getString("formula", "EPLEY")!!) }.getOrDefault(E1rmFormula.EPLEY),
            maxRepsForE1rm = prefs.getInt("maxReps", 12),
        ),
    )

    fun save(s: UserSettings) = prefs.edit()
        .putString("unit", s.unit.name)
        .putString("formula", s.analysis.formula.name)
        .putInt("maxReps", s.analysis.maxRepsForE1rm)
        .apply()
}

data class SyncProgress(val message: String, val done: Int = 0, val total: Int = 0)

class Repository(context: Context) {
    private val file = File(context.filesDir, "strength_data.json")
    private val rawDir = File(context.filesDir, "raw_exercise_sets")
    val tokenStore = PrefsTokenStore(context)
    val client = GarminClient(tokenStore)

    @Synchronized
    fun load(): StoreFile =
        if (!file.exists()) StoreFile()
        else runCatching { GarminParsing.json.decodeFromString<StoreFile>(file.readText()) }.getOrDefault(StoreFile())

    @Synchronized
    private fun save(store: StoreFile) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(GarminParsing.json.encodeToString(store))
        tmp.renameTo(file)
    }

    /**
     * Download strength activities and their sets.
     *
     * Incremental by default: paging stops at the first page whose activities
     * are all already stored. [full] re-downloads everything, which picks up
     * sets edited in Garmin Connect after the first sync.
     */
    fun sync(full: Boolean, onProgress: (SyncProgress) -> Unit): StoreFile {
        var store = load()
        val known = if (full) emptySet() else store.workouts.map { it.activityId }.toSet()

        onProgress(SyncProgress("Listing strength activities…"))
        val pending = mutableListOf<io.github.mathiashuber.gymsuite.garmin.ActivitySummary>()
        var start = 0
        val pageSize = 100
        while (true) {
            val page = client.activityPage(start, pageSize)
            if (page.isEmpty()) break
            val strength = page.filter { it.typeKey == "strength_training" }
            val fresh = strength.filter { it.activityId !in known }
            pending += fresh
            onProgress(SyncProgress("Found ${pending.size} new strength activities…"))
            // Incremental sync stops once it reaches strength workouts it already has.
            if (strength.isNotEmpty() && fresh.isEmpty()) break
            if (page.size < pageSize) break
            start += pageSize
        }

        val byId = store.workouts.associateBy { it.activityId }.toMutableMap()
        rawDir.mkdirs()
        pending.forEachIndexed { i, a ->
            onProgress(SyncProgress("Downloading sets: ${a.startTimeLocal.take(10)} ${a.name}", i, pending.size))
            val raw = client.exerciseSetsRaw(a.activityId)
            // Keep Garmin's untouched response too, for anything the parsed model drops.
            File(rawDir, "${a.activityId}.json").writeText(raw)
            byId[a.activityId] = Workout(a.activityId, a.name, a.startTimeLocal, a.durationSec, GarminParsing.parseExerciseSets(raw))
            if ((i + 1) % 20 == 0) save(store.copy(workouts = byId.values.toList()))
        }
        store = StoreFile(lastSyncEpochMs = System.currentTimeMillis(), workouts = byId.values.sortedByDescending { it.startTimeLocal })
        save(store)
        onProgress(SyncProgress("Synced ${pending.size} activities", pending.size, pending.size))
        return store
    }

    fun exportJson(): String = GarminParsing.json.encodeToString(load())

    fun clearData() {
        file.delete()
        rawDir.deleteRecursively()
    }
}
