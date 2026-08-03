package bot.toby.handler

import common.logging.DiscordLogger
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.utils.data.DataArray
import net.dv8tion.jda.api.utils.data.DataObject
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Registers the application's global commands in one bulk overwrite,
 * carrying the Activity **Entry Point** command through untouched.
 *
 * Why this isn't just `jda.updateCommands()`:
 *
 * Enabling Activities in the Developer Portal auto-creates a
 * PRIMARY_ENTRY_POINT command ("Launch") — the thing that makes the activity
 * launchable from the App Launcher at all. JDA 6.3.1 has no
 * `Command.Type.PRIMARY_ENTRY_POINT`, so that command cannot be expressed as
 * `CommandData` and cannot be included in JDA's bulk update. Discord now
 * *rejects* any bulk overwrite that would drop it:
 *
 * ```
 * 50240: You cannot remove this app's Entry Point command in a bulk update
 * operation. Please include the Entry Point command in your update request
 * or delete it separately.
 * ```
 *
 * The whole PUT fails, so **no** commands get registered — new commands never
 * appear and existing ones are frozen at whatever was registered before
 * Discord started enforcing this. (The previous approach — let the bulk update
 * delete it, then POST it back — stopped working the moment that enforcement
 * landed.)
 *
 * So the overwrite is issued over the raw REST API instead: read the current
 * global commands, keep any Entry Point command verbatim, and PUT it back
 * alongside everything JDA serialises for us. One request, nothing deleted,
 * no window where the activity is unlaunchable.
 *
 * Applications without Activities enabled simply have no Entry Point command
 * to find, and the request is exactly the payload JDA would have sent.
 */
@Service
class GlobalCommandRegistrar(
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) {

    /**
     * @return true when Discord accepted the overwrite.
     */
    fun register(jda: JDA, commands: List<CommandData>): CompletableFuture<Boolean> {
        val applicationId = jda.selfUser.applicationId
        val endpoint = "$API_BASE/applications/$applicationId/commands"

        return fetchExisting(jda, endpoint)
            .thenCompose { existing ->
                val entryPoint = existing.firstOrNull { it.getInt("type", 0) == PRIMARY_ENTRY_POINT_TYPE }
                if (entryPoint != null) {
                    logger.info { "Preserving Entry Point command '${entryPoint.getString("name", "?")}'" }
                }

                val payload = DataArray.empty()
                commands.forEach { payload.add(it.toData()) }
                entryPoint?.let { payload.add(it) }

                put(jda, endpoint, payload.toString(), commands.size)
            }
    }

    /**
     * Current global commands, or empty when the read fails. Failing soft is
     * deliberate: an unreadable list shouldn't block the overwrite, and the
     * worst case is the pre-existing 50240 we're already handling below.
     */
    private fun fetchExisting(jda: JDA, endpoint: String): CompletableFuture<List<DataObject>> {
        val request = HttpRequest.newBuilder(URI.create(endpoint))
            .header("Authorization", authHeader(jda))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).handle { response, error ->
            when {
                error != null -> {
                    logger.warn { "Could not read existing global commands: ${error.message}" }
                    emptyList()
                }
                response.statusCode() !in 200..299 -> {
                    logger.warn {
                        "Could not read existing global commands (status ${response.statusCode()}): ${response.body()}"
                    }
                    emptyList()
                }
                else -> runCatching {
                    val array = DataArray.fromJson(response.body())
                    (0 until array.length()).map { array.getObject(it) }
                }.getOrElse {
                    logger.warn { "Could not parse existing global commands: ${it.message}" }
                    emptyList()
                }
            }
        }
    }

    private fun put(jda: JDA, endpoint: String, body: String, commandCount: Int): CompletableFuture<Boolean> {
        val request = HttpRequest.newBuilder(URI.create(endpoint))
            .header("Authorization", authHeader(jda))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).handle { response, error ->
            when {
                error != null -> {
                    logger.error { "Failed to register global commands: ${error.message}" }
                    false
                }
                response.statusCode() in 200..299 -> {
                    logger.info { "Registered $commandCount global commands" }
                    true
                }
                else -> {
                    logger.error {
                        "Discord rejected the global command overwrite " +
                            "(status ${response.statusCode()}): ${response.body()}"
                    }
                    false
                }
            }
        }
    }

    // JDA may or may not hand the token back with the "Bot " prefix depending
    // on how it was supplied — normalise either way.
    private fun authHeader(jda: JDA) = "Bot ${jda.token.removePrefix("Bot ").trim()}"

    companion object {
        private val logger: DiscordLogger = DiscordLogger.createLogger(GlobalCommandRegistrar::class.java)

        const val API_BASE = "https://discord.com/api/v10"

        /** Discord's application command type 4. */
        const val PRIMARY_ENTRY_POINT_TYPE = 4
    }
}
