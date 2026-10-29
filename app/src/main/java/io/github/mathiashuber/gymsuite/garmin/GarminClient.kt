package io.github.mathiashuber.gymsuite.garmin

import io.github.mathiashuber.gymsuite.data.StrengthSet
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Where the client keeps its tokens; implemented on SharedPreferences in the app. */
interface TokenStore {
    fun load(): GarminTokens?
    fun save(tokens: GarminTokens?)
}

class GarminApiException(val status: Int, message: String) : IOException(message)

/** Minimal connectapi client: just the endpoints the gym suite needs. */
class GarminClient(private val store: TokenStore) {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val lock = Any()

    val isAuthenticated: Boolean get() = store.load() != null

    fun signIn(ticket: String, flow: LoginFlow) {
        store.save(GarminAuth.exchangeServiceTicket(http, ticket, flow.serviceUrl))
    }

    fun signOut() = store.save(null)

    private fun currentTokens(forceRefresh: Boolean): GarminTokens = synchronized(lock) {
        val tokens = store.load() ?: throw GarminAuthException("Not signed in")
        if (!forceRefresh && !GarminAuth.expiresSoon(tokens.di_token)) return tokens
        GarminAuth.refresh(http, tokens).also { store.save(it) }
    }

    private fun get(path: String, params: Map<String, String> = emptyMap()): String {
        val url = (GarminConstants.CONNECT_API + path).toHttpUrl().newBuilder()
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        var tokens = currentTokens(forceRefresh = false)
        repeat(2) { attempt ->
            val req = Request.Builder().url(url).apply {
                GarminConstants.NATIVE_HEADERS.forEach { (k, v) -> header(k, v) }
                header("Authorization", "Bearer ${tokens.di_token}")
                header("Accept", "application/json")
            }.build()
            val (code, body) = http.newCall(req).execute().use { r -> r.code to (r.body?.string() ?: "") }
            if (code == 401 && attempt == 0) {
                tokens = currentTokens(forceRefresh = true)
                return@repeat
            }
            if (code == 204) return "{}"
            if (code in 200..299) return body
            throw GarminApiException(code, "Garmin API error $code for $path")
        }
        throw GarminApiException(401, "Garmin rejected the session; please sign in again")
    }

    /** A page of strength training activities, newest first. */
    fun strengthActivities(start: Int, limit: Int): List<ActivitySummary> =
        GarminParsing.parseActivityList(
            get(
                "/activitylist-service/activities/search/activities",
                mapOf("start" to "$start", "limit" to "$limit", "activityType" to "strength_training"),
            ),
        ).filter { it.typeKey.isEmpty() || it.typeKey == "strength_training" }

    fun exerciseSetsRaw(activityId: Long): String = get("/activity-service/activity/$activityId/exerciseSets")

    fun exerciseSets(activityId: Long): List<StrengthSet> = GarminParsing.parseExerciseSets(exerciseSetsRaw(activityId))
}
