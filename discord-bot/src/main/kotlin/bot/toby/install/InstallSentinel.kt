package bot.toby.install

import database.dto.guild.ConfigDto.Configurations
import database.service.guild.ConfigService

/**
 * Records the install sentinel (`INSTALL_MODE` + `INSTALLED_AT`) the
 * first time an owner completes the wizard, and is a no-op on every
 * subsequent run. Owners who flip from Express to Custom (or vice-versa)
 * via a second `/install` keep their original sentinel — the wizard
 * doesn't reach for downgrade semantics that aren't there.
 *
 * Idempotency is value-agnostic: presence of `INSTALL_MODE` is the
 * signal. Matches the same check in
 * [InstallWelcomeHandler.onGuildJoin].
 */
object InstallSentinel {
    fun writeIfFresh(configService: ConfigService, guildId: String, mode: String) {
        val existing = configService.getConfigByName(Configurations.INSTALL_MODE.configValue, guildId)?.value
        if (!existing.isNullOrBlank()) return
        configService.upsertAll(
            guildId,
            listOf(
                Configurations.INSTALL_MODE.configValue to mode,
                Configurations.INSTALLED_AT.configValue to System.currentTimeMillis().toString(),
            ),
        )
    }
}
