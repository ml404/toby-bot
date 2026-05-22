package bot.toby.install

import database.dto.ConfigDto.Configurations
import database.service.ConfigService

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
        configService.upsertConfig(Configurations.INSTALL_MODE.configValue, mode, guildId)
        configService.upsertConfig(
            Configurations.INSTALLED_AT.configValue,
            System.currentTimeMillis().toString(),
            guildId,
        )
    }
}
