package web.activity

import com.fasterxml.jackson.databind.ObjectMapper
import common.logging.DiscordLogger
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Auth bridge for the Discord Activity (Embedded App SDK) surface.
 *
 * Inside the Activity iframe the normal redirect-based OAuth2 login can't
 * run (the iframe is sandboxed on `*.discordsays.com` and session cookies
 * are third-party there), so the SDK flow is used instead:
 *
 *  1. The shell page calls `sdk.commands.authorize()` and receives an
 *     OAuth2 authorization code.
 *  2. It POSTs the code to `/activity/api/token`; [exchange] swaps it for
 *     a Discord access token (same client id/secret the web login uses),
 *     resolves the user via `/users/@me`, and mints an opaque session
 *     token bound to that Discord identity.
 *  3. The Discord access token goes back to the shell (the SDK's
 *     `authenticate()` needs it); the session token authenticates every
 *     subsequent request via [ActivityTokenAuthFilter].
 *
 * Sessions live in memory — the app runs as a single dyno, and an
 * activity session is ephemeral by nature (re-launching the activity
 * re-runs the silent `prompt:'none'` authorize). TTL follows Discord's
 * token expiry, capped at [MAX_SESSION_TTL_MS].
 */
interface ActivitySessions {
    /** Swap an SDK authorization code for a session; null on any failure. */
    fun exchange(code: String): Issued?

    /** Resolve a previously issued session token; null if unknown/expired. */
    fun resolve(sessionToken: String): OAuth2User?

    data class Issued(val sessionToken: String, val accessToken: String)
}

@Component
class ActivitySessionService(
    @param:Value($$"${spring.security.oauth2.client.registration.discord.client-id:}") clientId: String = "",
    @param:Value($$"${spring.security.oauth2.client.registration.discord.client-secret:}") clientSecret: String = "",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : ActivitySessions {

    // Trim defensively — same rationale as RedditTokenProvider: env vars
    // pasted into a host's config often carry trailing whitespace.
    private val clientId: String = clientId.trim()
    private val clientSecret: String = clientSecret.trim()

    private val jackson = ObjectMapper()
    private val random = SecureRandom()

    private data class Entry(val principal: OAuth2User, val expiresAtMs: Long)

    private val sessions = ConcurrentHashMap<String, Entry>()

    override fun exchange(code: String): ActivitySessions.Issued? {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            logger.error { "Activity token exchange attempted but Discord OAuth2 client is not configured" }
            return null
        }
        return try {
            val (accessToken, expiresInSec) = fetchAccessToken(code) ?: return null
            val principal = fetchPrincipal(accessToken) ?: return null
            purgeExpired()
            val sessionToken = mintToken()
            val ttlMs = (expiresInSec * 1000).coerceAtMost(MAX_SESSION_TTL_MS)
            sessions[sessionToken] = Entry(principal, nowMs() + ttlMs)
            // INFO on purpose: one line per activity launch in the deploy
            // logs makes "did the handshake reach the server?" answerable
            // without a debugger in the Discord client.
            logger.info { "Activity session minted for discord user ${principal.name}" }
            ActivitySessions.Issued(sessionToken, accessToken)
        } catch (e: Exception) {
            logger.error { "Activity token exchange failed: ${e.message}" }
            null
        }
    }

    override fun resolve(sessionToken: String): OAuth2User? {
        val entry = sessions[sessionToken] ?: return null
        if (nowMs() >= entry.expiresAtMs) {
            sessions.remove(sessionToken)
            return null
        }
        return entry.principal
    }

    private fun fetchAccessToken(code: String): Pair<String, Long>? {
        // The SDK's authorize() flow has no redirect — Discord's token
        // endpoint accepts the exchange without a redirect_uri here.
        val form = listOf(
            "client_id" to clientId,
            "client_secret" to clientSecret,
            "grant_type" to "authorization_code",
            "code" to code,
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }

        val request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val node = jackson.readTree(response.body())
        val accessToken = node.path("access_token").takeIf { it.isTextual }?.asText()
        if (accessToken == null) {
            logger.error { "Discord token endpoint returned no access_token (status ${response.statusCode()})" }
            return null
        }
        return accessToken to node.path("expires_in").asLong(DEFAULT_EXPIRY_SEC)
    }

    private fun fetchPrincipal(accessToken: String): OAuth2User? {
        val request = HttpRequest.newBuilder(URI.create(USER_INFO_URL))
            .header("Authorization", "Bearer $accessToken")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val node = jackson.readTree(response.body())
        val id = node.path("id").takeIf { it.isTextual }?.asText()
        if (id == null) {
            logger.error { "Discord /users/@me returned no id (status ${response.statusCode()})" }
            return null
        }
        // Mirrors the attribute shape Spring's oauth2Login produces for the
        // Discord provider (user-name-attribute=id), so discordIdOrNull()
        // and displayName() work unchanged on both auth paths.
        val attributes = mapOf(
            "id" to id,
            "username" to (node.path("username").takeIf { it.isTextual }?.asText() ?: "User"),
        )
        return DefaultOAuth2User(setOf(SimpleGrantedAuthority("OAUTH2_USER")), attributes, "id")
    }

    private fun mintToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun purgeExpired() {
        val now = nowMs()
        sessions.entries.removeIf { it.value.expiresAtMs <= now }
    }

    companion object {
        private val logger: DiscordLogger = DiscordLogger.createLogger(ActivitySessionService::class.java)

        const val TOKEN_URL = "https://discord.com/api/oauth2/token"
        const val USER_INFO_URL = "https://discord.com/api/users/@me"

        /**
         * Prefix on every issued session token. Lets the auth filter and
         * the CSRF exemption matcher cheaply recognise activity bearer
         * tokens without a map lookup.
         */
        const val TOKEN_PREFIX = "act_"

        private const val MAX_SESSION_TTL_MS = 12 * 60 * 60 * 1000L
        private const val DEFAULT_EXPIRY_SEC = 3600L
    }
}
