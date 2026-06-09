package bot.toby.command.commands.fetch

import com.google.gson.JsonParser
import common.logging.DiscordLogger
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Base64

/**
 * Supplies an application-only (client-credentials) OAuth bearer token for
 * Reddit's API. Reddit dropped unauthenticated `.json` access, so `/meme`
 * needs a token to work (issue #107).
 *
 * Configure with a Reddit "script"/"web app" client id + secret via the
 * `REDDIT_CLIENT_ID` / `REDDIT_CLIENT_SECRET` env vars (mapped to
 * `reddit.client-id` / `reddit.client-secret`). When unset, [isConfigured]
 * is false and [bearerToken] returns null — the fetcher then degrades to
 * the legacy unauthenticated endpoint (which Reddit now blocks), so the
 * command behaves exactly as before until credentials are provided.
 *
 * Tokens are cached and refreshed shortly before expiry; the refresh is
 * guarded by a [Mutex] so a burst of `/meme` calls triggers one fetch.
 */
@Component
class RedditTokenProvider @Autowired constructor(
    private val client: HttpClient,
    @param:Value($$"${reddit.client-id:}") clientId: String = "",
    @param:Value($$"${reddit.client-secret:}") clientSecret: String = "",
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    // Trim defensively: env vars pasted into a host's config (e.g. Heroku)
    // often carry a trailing newline/space, which would corrupt the Basic
    // auth header and earn a 401 from Reddit's token endpoint.
    private val clientId: String = clientId.trim()
    private val clientSecret: String = clientSecret.trim()

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)
    private val mutex = Mutex()

    @Volatile private var cachedToken: String? = null
    @Volatile private var expiresAtMs: Long = 0L

    val isConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    /**
     * A valid app-only bearer token, fetched/refreshed as needed. Returns
     * null when unconfigured or when the token request fails (logged).
     */
    suspend fun bearerToken(): String? {
        if (!isConfigured) return null
        return mutex.withLock {
            cachedToken?.let { if (nowMs() < expiresAtMs - REFRESH_SKEW_MS) return@withLock it }
            runCatching { fetchToken() }
                .onFailure { logger.error { "Failed to obtain Reddit app token: ${it.message}" } }
                .getOrNull()
        }
    }

    private suspend fun fetchToken(): String {
        val basic = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
        val response = client.post(TOKEN_URL) {
            header(HttpHeaders.Authorization, "Basic $basic")
            header(HttpHeaders.UserAgent, USER_AGENT)
            setBody(FormDataContent(Parameters.build { append("grant_type", "client_credentials") }))
        }
        val json = JsonParser.parseString(response.bodyAsText()).asJsonObject
        val token = json.get("access_token")?.takeIf { !it.isJsonNull }?.asString
            ?: error("no access_token in Reddit token response (status ${response.status.value})")
        val expiresInSec = json.get("expires_in")?.takeIf { !it.isJsonNull }?.asLong ?: 3600L
        cachedToken = token
        expiresAtMs = nowMs() + expiresInSec * 1000
        logger.info { "Obtained Reddit app token (expires in ${expiresInSec}s)" }
        return token
    }

    companion object {
        const val TOKEN_URL = "https://www.reddit.com/api/v1/access_token"

        /** Reddit requires a unique, descriptive User-Agent or it aggressively rate-limits. */
        const val USER_AGENT = "discord:co.uk.toby-bot:1.0 (by /u/toby-bot)"

        private const val REFRESH_SKEW_MS = 60_000L
    }
}
