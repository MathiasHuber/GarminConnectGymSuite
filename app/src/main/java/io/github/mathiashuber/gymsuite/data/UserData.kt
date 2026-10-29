package io.github.mathiashuber.gymsuite.data

import kotlinx.serialization.Serializable

/** A user-defined set of Garmin exercise keys analysed as one exercise. */
@Serializable
data class ExerciseGroup(
    val id: String,
    val name: String,
    val members: List<String>,
)

/**
 * Everything the user curates on top of the Garmin data. Kept in its own file
 * so a resync or deleting the downloaded workouts never touches it.
 *
 * A "series" id is what the UI lists and charts: either a Garmin exercise key
 * or [GROUP_PREFIX] + group id.
 */
@Serializable
data class UserData(
    val version: Int = 1,
    val favorites: Set<String> = emptySet(),
    val groups: List<ExerciseGroup> = emptyList(),
    val excludedWorkouts: Set<Long> = emptySet(),
    /** Set references from [setRef]. */
    val excludedSets: Set<String> = emptySet(),
    /** Era names chosen by the user, keyed by the era's first activity id. */
    val eraNames: Map<String, String> = emptyMap(),
    /** Activities where the user forced a new era to start. */
    val eraSplits: Set<Long> = emptySet(),
    /** First activities of eras the user merged into the era before. */
    val eraMerges: Set<Long> = emptySet(),
    /** Era detection detail, 1 (fewest eras) to 5 (most eras). */
    val eraDetail: Int = 3,
) {
    fun group(seriesId: String): ExerciseGroup? =
        if (seriesId.startsWith(GROUP_PREFIX)) groups.firstOrNull { GROUP_PREFIX + it.id == seriesId } else null

    fun groupOfKey(key: String): ExerciseGroup? = groups.firstOrNull { key in it.members }

    /** The series an exercise key is analysed under. */
    fun seriesOf(key: String): String = groupOfKey(key)?.let { GROUP_PREFIX + it.id } ?: key

    /** Exercise keys that make up a series. */
    fun membersOf(seriesId: String): Set<String> =
        group(seriesId)?.members?.toSet() ?: if (seriesId.startsWith(GROUP_PREFIX)) emptySet() else setOf(seriesId)

    fun displayName(seriesId: String): String = group(seriesId)?.name ?: exerciseDisplayName(seriesId)

    fun isWorkoutExcluded(activityId: Long) = activityId in excludedWorkouts

    fun isSetExcluded(activityId: Long, set: StrengthSet) =
        activityId in excludedWorkouts || setRef(activityId, set.index) in excludedSets

    /** Workouts with excluded workouts and sets removed; set indexes are kept. */
    fun applyExclusions(workouts: List<Workout>): List<Workout> {
        if (excludedWorkouts.isEmpty() && excludedSets.isEmpty()) return workouts
        return workouts.filter { it.activityId !in excludedWorkouts }.map { w ->
            w.copy(sets = w.sets.filter { setRef(w.activityId, it.index) !in excludedSets })
        }
    }

    fun toggleFavorite(seriesId: String) =
        copy(favorites = if (seriesId in favorites) favorites - seriesId else favorites + seriesId)

    fun setFavorite(seriesIds: Collection<String>, favorite: Boolean) =
        copy(favorites = if (favorite) favorites + seriesIds else favorites - seriesIds.toSet())

    fun toggleWorkout(activityId: Long) = copy(
        excludedWorkouts = if (activityId in excludedWorkouts) excludedWorkouts - activityId else excludedWorkouts + activityId,
    )

    fun toggleSet(activityId: Long, index: Int): UserData {
        val ref = setRef(activityId, index)
        return copy(excludedSets = if (ref in excludedSets) excludedSets - ref else excludedSets + ref)
    }

    /**
     * Make a group from the given series (exercises or existing groups, which
     * are merged into it). Members leave any group they were in before; the
     * new group is a favorite if any of its parts was.
     */
    fun createGroup(id: String, name: String, seriesIds: Collection<String>): UserData {
        val keys = seriesIds.flatMap { membersOf(it) }.distinct()
        val wasFavorite = seriesIds.any { it in favorites }
        val absorbed = seriesIds.filter { it.startsWith(GROUP_PREFIX) }.toSet()
        val newId = GROUP_PREFIX + id
        return copy(
            groups = groups.mapNotNull { g ->
                if (GROUP_PREFIX + g.id in absorbed) null else g.copy(members = g.members - keys.toSet())
            } + ExerciseGroup(id, name.trim().ifEmpty { "Group" }, keys),
            favorites = (favorites - seriesIds.toSet()).let { if (wasFavorite) it + newId else it },
        ).pruned()
    }

    fun addToGroup(groupId: String, key: String) = copy(
        groups = groups.map { g ->
            if (g.id == groupId) g.copy(members = (g.members - key) + key) else g.copy(members = g.members - key)
        },
    ).pruned()

    fun removeFromGroup(groupId: String, key: String) =
        copy(groups = groups.map { if (it.id == groupId) it.copy(members = it.members - key) else it }).pruned()

    fun renameGroup(groupId: String, name: String) =
        copy(groups = groups.map { if (it.id == groupId) it.copy(name = name.trim().ifEmpty { it.name }) else it })

    /** Dissolve a group; its exercises become separate again. */
    fun deleteGroup(groupId: String) = copy(
        groups = groups.filter { it.id != groupId },
        favorites = favorites - (GROUP_PREFIX + groupId),
    )

    /** Drop empty groups and favorites pointing at groups that no longer exist. */
    private fun pruned(): UserData {
        val kept = groups.filter { it.members.isNotEmpty() }
        val ids = kept.map { GROUP_PREFIX + it.id }.toSet()
        return copy(groups = kept, favorites = favorites.filter { !it.startsWith(GROUP_PREFIX) || it in ids }.toSet())
    }

    fun renameEra(key: Long, name: String?) = copy(
        eraNames = if (name.isNullOrBlank()) eraNames - key.toString() else eraNames + (key.toString() to name.trim()),
    )

    fun mergeEraIntoPrevious(key: Long) = copy(eraMerges = eraMerges + key, eraSplits = eraSplits - key)

    fun startEraAt(activityId: Long) = copy(eraSplits = eraSplits + activityId, eraMerges = eraMerges - activityId)

    /** Forget all era names and boundary edits. */
    fun resetEras() = copy(eraNames = emptyMap(), eraSplits = emptySet(), eraMerges = emptySet())

    companion object {
        const val GROUP_PREFIX = "group:"
    }
}

fun setRef(activityId: Long, index: Int) = "$activityId#$index"
