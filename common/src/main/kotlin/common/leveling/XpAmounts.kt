package common.leveling

/**
 * Shared XP grant constants used by every user-action XP source.
 *
 * Discord's [bot.toby.handler.SlashCommandEventListener] and the web
 * surface's gameplay/economy interceptor both pay [COMMAND_XP] per
 * user-initiated action so the two surfaces stay symmetrical. The daily
 * cap (see [database.service.XpAwardService]) prevents either source from
 * being farmed.
 */
object XpAmounts {
    const val COMMAND_XP: Long = 5L
}
