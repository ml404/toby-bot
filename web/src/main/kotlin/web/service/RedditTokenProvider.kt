package web.service

import com.fasterxml.jackson.databind.ObjectMapper
import common.logging.DiscordLogger
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * App-only (client-credentials) OAuth bearer-token source for Reddit's API,
 * used by the web `/utils/api/meme` tool.
 *
 * Reddit dropped unauthenticated `.json` access, so the meme fetcher needs a
 * token. The Discord `/meme` command was migrated to OAuth in issue #107, but
 * the web tool was left on the now-blocked anonymous endpoint — Reddit answers
 * those with a 403, which [UtilsWebService] surfaced to the browser as a 400
 * (issue #403). This provider closes that gap using the *same*
 * `REDDIT_CLIENT_ID` / `REDDIT_CLIENT_SECRET` env vars (mapped to
 * `reddit.client-id` / `reddit.client-secret`) the bot already consumes.
 *
 * When unset, [isConfigured] is false and [bearerToken] returns null, so the
 * fetcher degrades to the legacy anonymous endpoint exactly as before.
 *
 * Tokens are cached and refreshed shortly before expiry; the refresh is guarded
 * by a monitor so a burst of meme calls triggers a single fetch.
 */
interface RedditTokenSource {
    /** True once both client id and secret are present. */
    val isConfigured: Boolean

    /** A valid app-only bearer token, or null when unconfigured / the token request failed. */
    fun bearerToken(): String?
}

// Explicit bean name: the discord-bot module also has a `RedditTokenProvider`
// @Component, and the full application context scans both `bot.toby.*` and
// `web.*`. Without a distinct name Spring derives `redditTokenProvider` for
// both and fails to load the context (issue #403 follow-up).
@Component("webRedditTokenProvider")
class RedditTokenProvider(
    @param:Value($$"${reddit.client-id:}") clientId: String = "",
    @param:Value($$"${reddit.client-secret:}") clientSecret: String = "",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : RedditTokenSource {

    // Trim defensively: env vars pasted into a host's config (e.g. Heroku)
    // often carry a trailing newline/space, which would corrupt the Basic
    // auth header and earn a 401 from Reddit's token endpoint.
    private val clientId: String = clientId.trim()
    private val clientSecret: String = clientSecret.trim()

    private val jackson = ObjectMapper()
    private val lock = Any()

    @Volatile private var cachedToken: String? = null
    @Volatile private var expiresAtMs: Long = 0L

    override val isConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    override fun bearerToken(): String? {
        if (!isConfigured) return null
        synchronized(lock) {
            cachedToken?.let { if (nowMs() < expiresAtMs - REFRESH_SKEW_MS) return it }
            return try {
                fetchToken()
            } catch (e: Exception) {
                logger.error { "Failed to obtain Reddit app token: ${e.message}" }
                null
            }
        }
    }

    private fun fetchToken(): String {
        val basic = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
        val request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
            .header("Authorization", "Basic $basic")
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val token = parseAccessToken(response.body())
            ?: error("no access_token in Reddit token response (status ${response.statusCode()})")
        cachedToken = token
        expiresAtMs = nowMs() + parseExpiresInSec(response.body()) * 1000
        logger.info { "Obtained Reddit app token" }
        return token
    }

    private fun parseAccessToken(body: String): String? = runCatching {
        jackson.readTree(body).path("access_token").takeIf { it.isTextual }?.asText()
    }.getOrNull()

    private fun parseExpiresInSec(body: String): Long = runCatching {
        jackson.readTree(body).path("expires_in").asLong(DEFAULT_EXPIRY_SEC)
    }.getOrDefault(DEFAULT_EXPIRY_SEC)

    companion object {
        private val logger: DiscordLogger = DiscordLogger.createLogger(RedditTokenProvider::class.java)

        const val TOKEN_URL = "https://www.reddit.com/api/v1/access_token"

        /** Reddit requires a unique, descriptive User-Agent or it aggressively rate-limits. */
        const val USER_AGENT = "web:co.uk.toby-bot:1.0 (by /u/toby-bot)"

        private const val REFRESH_SKEW_MS = 60_000L
        private const val DEFAULT_EXPIRY_SEC = 3600L
    }
}
