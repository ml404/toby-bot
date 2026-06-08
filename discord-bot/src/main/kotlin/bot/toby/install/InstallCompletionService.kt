package bot.toby.install

import common.logging.DiscordLogger
import database.dto.guild.ConfigDto.Configurations
import database.service.economy.JackpotService
import database.service.guild.AchievementService
import database.service.guild.ConfigService
import net.dv8tion.jda.api.entities.Guild
import org.springframework.stereotype.Service

/**
 * The "install just completed" hook, shared by the Express and Custom
 * (Finish) buttons. Beyond stamping the install sentinel, it makes the
 * payoff *immediate and visible* for a freshly-onboarded server:
 *
 *  - unlocks the owner's "Welcome Aboard" achievement (which fires the
 *    full DM / channel / web-push notification pipeline), so the very
 *    first thing the owner sees is the bot's reward machinery working; and
 *  - seeds the server jackpot with a starting pot so the casino opens with
 *    a live, non-zero number instead of looking dead on the first game.
 *
 * Both side-effects are best-effort: a failure in either is logged and
 * swallowed so it can never break the install UX (mirroring the defensive
 * posture in [InstallWelcomeHandler]).
 */
@Service
class InstallCompletionService(
    private val configService: ConfigService,
    private val jackpotService: JackpotService,
    private val achievementService: AchievementService,
) {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    fun complete(guild: Guild, mode: String, channelId: Long?) {
        // First-EVER install for this guild? INSTALLED_AT survives a
        // guild-leave (only INSTALL_MODE is cleared on leave), so its
        // absence — read BEFORE writeIfFresh stamps it — is the one true
        // "never onboarded before" signal. The jackpot seed is gated on
        // this so a leave/re-invite cycle can't farm the pool.
        val firstEverInstall = configService
            .getConfigByName(Configurations.INSTALLED_AT.configValue, guild.id)?.value
            .isNullOrBlank()

        InstallSentinel.writeIfFresh(configService, guild.id, mode)

        // Celebratory unlock for the owner. unlock() is idempotent — it
        // publishes no event and re-awards nothing once owned — so a second
        // /install never re-notifies or double-pays.
        runCatching {
            achievementService.unlock(guild.ownerIdLong, guild.idLong, INSTALL_ACHIEVEMENT_CODE, channelId)
        }.onFailure { logger.error { "Install achievement grant failed for guild ${guild.id}: ${it.message}" } }

        if (firstEverInstall) {
            runCatching { jackpotService.addToPool(guild.idLong, JACKPOT_SEED_AMOUNT) }
                .onFailure { logger.error { "Jackpot seed failed for guild ${guild.id}: ${it.message}" } }
        }
    }

    companion object {
        /** Must match the `code` of the milestone achievement in `AchievementCatalog`. */
        const val INSTALL_ACHIEVEMENT_CODE: String = "install_complete"

        /** Starting jackpot pot (credits) so the casino opens with a live number. */
        const val JACKPOT_SEED_AMOUNT: Long = 1000L
    }
}
