package io.github.mathiashuber.gymsuite.garmin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Base64

/**
 * Garmin Connect authentication, ported from python-garminconnect 0.3.x
 * (https://github.com/cyberjunky/python-garminconnect, client/session.py).
 *
 * Login itself happens in a WebView on Garmin's own SSO page, which lets
 * Garmin run its bot checks, captcha and MFA in a real browser. What we get
 * out of it is a CAS service ticket (ST-...), which is exchanged here for the
 * same DI OAuth2 bearer token the Garmin Connect mobile app uses.
 */
object GarminConstants {
    const val SSO = "https://sso.garmin.com"
    const val CONNECT_API = "https://connectapi.garmin.com"
    const val DI_TOKEN_URL = "https://diauth.garmin.com/di-oauth2-service/oauth/token"
    const val DI_GRANT_TYPE = "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket"
    val DI_CLIENT_IDS = listOf(
        "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
        "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
        "GARMIN_CONNECT_MOBILE_ANDROID_DI",
        "GARMIN_CONNECT_MOBILE_IOS_DI",
    )

    val NATIVE_HEADERS = mapOf(
        "User-Agent" to "GCM-Android-5.23",
        "X-Garmin-User-Agent" to "com.garmin.android.apps.connectmobile/5.23; ; Google/sdk_gphone64_arm64/google; Android/33; Dalvik/2.1.0",
        "X-Garmin-Paired-App-Version" to "10861",
        "X-Garmin-Client-Platform" to "Android",
        "X-App-Ver" to "10861",
        "X-Lang" to "en",
        "X-GCExperience" to "GC5",
        "Accept-Language" to "en-US,en;q=0.9",
    )
}

/** The two SSO entry points the python library logs in through. */
enum class LoginFlow(val label: String, val clientId: String, val serviceUrl: String, val signInPath: String) {
    MOBILE("Mobile sign-in", "GCM_IOS_DARK", "https://mobile.integration.garmin.com/gcm/ios", "/mobile/sso/en_US/sign-in"),
    PORTAL("Web sign-in", "GarminConnect", "https://connect.garmin.com/app", "/portal/sso/en-US/sign-in");

    val signInUrl: String
        get() = GarminConstants.SSO + signInPath +
            "?clientId=" + java.net.URLEncoder.encode(clientId, "UTF-8") +
            "&service=" + java.net.URLEncoder.encode(serviceUrl, "UTF-8")
}

/** Same shape as python-garminconnect's garmin_tokens.json, so tokens can be imported from it. */
@Serializable
data class GarminTokens(
    val di_token: String,
    val di_refresh_token: String? = null,
    val di_client_id: String? = null,
)

class GarminAuthException(message: String, cause: Throwable? = null) : IOException(message, cause)

object GarminAuth {

    private fun basicAuth(clientId: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$clientId:".toByteArray())

    private fun jwtPayload(token: String): Map<String, JsonPrimitive>? = runCatching {
        val part = token.split('.')[1]
        val decoded = String(Base64.getUrlDecoder().decode(part.padEnd((part.length + 3) / 4 * 4, '=')))
        GarminParsing.json.parseToJsonElement(decoded).let { el ->
            (el as kotlinx.serialization.json.JsonObject).mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.let { k to it }
            }.toMap()
        }
    }.getOrNull()

    fun clientIdFromJwt(token: String): String? = jwtPayload(token)?.get("client_id")?.contentOrNull

    /** Expiry of the access token in epoch seconds, if the JWT carries one. */
    fun expiresAt(token: String): Long? =
        jwtPayload(token)?.get("exp")?.let { it.longOrNull ?: it.content.toDoubleOrNull()?.toLong() }

    fun expiresSoon(token: String, nowEpochSec: Long = System.currentTimeMillis() / 1000): Boolean {
        val exp = expiresAt(token) ?: return false
        return nowEpochSec > exp - 900
    }

    private fun tokenPost(http: OkHttpClient, clientId: String, form: Map<String, String>): Pair<Int, String> {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url(GarminConstants.DI_TOKEN_URL).post(body).apply {
            GarminConstants.NATIVE_HEADERS.forEach { (k, v) -> header(k, v) }
            header("Authorization", basicAuth(clientId))
            header("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
            header("Cache-Control", "no-cache")
        }.build()
        http.newCall(req).execute().use { r -> return r.code to (r.body?.string() ?: "") }
    }

    private fun parseTokenResponse(body: String, fallbackClientId: String, previousRefresh: String? = null): GarminTokens? {
        val o = GarminParsing.jsonObjectOrNull(body) ?: return null
        val access = o["access_token"]?.jsonPrimitive?.contentOrNull ?: return null
        val refresh = o["refresh_token"]?.jsonPrimitive?.contentOrNull ?: previousRefresh
        return GarminTokens(access, refresh, clientIdFromJwt(access) ?: fallbackClientId)
    }

    /** Exchange a CAS service ticket for DI OAuth2 tokens, trying each known client id. */
    fun exchangeServiceTicket(http: OkHttpClient, ticket: String, serviceUrl: String): GarminTokens {
        var lastStatus = 0
        for (clientId in GarminConstants.DI_CLIENT_IDS) {
            val (code, body) = tokenPost(
                http, clientId,
                mapOf(
                    "client_id" to clientId,
                    "service_ticket" to ticket,
                    "grant_type" to GarminConstants.DI_GRANT_TYPE,
                    "service_url" to serviceUrl,
                ),
            )
            if (code == 429) throw GarminAuthException("Garmin rate limited the token exchange (HTTP 429). Try again later.")
            lastStatus = code
            if (code !in 200..299) continue
            parseTokenResponse(body, clientId)?.let { return it }
        }
        throw GarminAuthException("Token exchange failed for all client ids (last HTTP $lastStatus)")
    }

    fun refresh(http: OkHttpClient, tokens: GarminTokens): GarminTokens {
        val refresh = tokens.di_refresh_token ?: throw GarminAuthException("No refresh token; please sign in again")
        val clientId = tokens.di_client_id ?: clientIdFromJwt(tokens.di_token)
            ?: throw GarminAuthException("Unknown client id; please sign in again")
        val (code, body) = tokenPost(
            http, clientId,
            mapOf("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refresh),
        )
        if (code !in 200..299) throw GarminAuthException("Token refresh failed (HTTP $code); please sign in again")
        return parseTokenResponse(body, clientId, refresh)
            ?: throw GarminAuthException("Token refresh returned no token")
    }
}
