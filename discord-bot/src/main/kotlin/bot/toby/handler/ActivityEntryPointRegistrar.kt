package bot.toby.handler

import common.logging.DiscordLogger
import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Re-creates the application's Activity Entry Point command after the
 * startup bulk command overwrite.
 *
 * Enabling Activities in the Developer Portal auto-creates a
 * PRIMARY_ENTRY_POINT command ("Launch") — the thing that makes the
 * activity launchable from the App Launcher / activity shelf at all.
 * But [StartUpHandler] registers slash commands with `updateCommands()`,
 * a bulk PUT that *replaces* the full global command set, which silently
 * deletes that command on every boot — leaving the casino activity
 * unlaunchable after the first redeploy. JDA 6.3.1 has no
 * PRIMARY_ENTRY_POINT command type, so the command can't be included in
 * the bulk update; instead this registrar POSTs it back via the raw REST
 * API once the overwrite has landed. POST upserts a single command by
 * name and leaves the rest of the set alone, so running it on every
 * boot is idempotent.
 *
 * Failure is non-fatal: if Activities aren't enabled for the app,
 * Discord rejects the create and the bot carries on with a warning.
 */
@Service
class ActivityEntryPointRegistrar(
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) {

    fun register(jda: JDA): CompletableFuture<Boolean> {
        val applicationId = jda.selfUser.applicationId
        // JDA may or may not hand the token back with the "Bot " prefix
        // depending on how it was supplied — normalise either way.
        val token = jda.token.removePrefix("Bot ").trim()
        val request = HttpRequest.newBuilder(URI.create("$API_BASE/applications/$applicationId/commands"))
            .header("Authorization", "Bot $token")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(ENTRY_POINT_COMMAND_JSON))
            .build()
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).handle { response, error ->
            when {
                error != null -> {
                    logger.error { "Failed to register activity Entry Point command: ${error.message}" }
                    false
                }

                response.statusCode() in 200..299 -> {
                    logger.info { "Registered activity Entry Point command 'launch'" }
                    true
                }

                else -> {
                    // Most likely cause: Activities aren't enabled for this
                    // application in the Developer Portal — harmless for
                    // deployments that don't use the activity surface.
                    logger.warn {
                        "Discord rejected the activity Entry Point command " +
                            "(status ${response.statusCode()}): ${response.body()}"
                    }
                    false
                }
            }
        }
    }

    companion object {
        private val logger: DiscordLogger = DiscordLogger.createLogger(ActivityEntryPointRegistrar::class.java)

        const val API_BASE = "https://discord.com/api/v10"

        // type 4 = PRIMARY_ENTRY_POINT; handler 2 = DISCORD_LAUNCH_ACTIVITY
        // (Discord opens the activity itself, no interaction round-trip).
        // Guild-install / guild-context only — the casino needs a guildId.
        val ENTRY_POINT_COMMAND_JSON = """
            {
              "name": "launch",
              "description": "Launch the TobyBot casino",
              "type": 4,
              "handler": 2,
              "integration_types": [0],
              "contexts": [0]
            }
        """.trimIndent()
    }
}
