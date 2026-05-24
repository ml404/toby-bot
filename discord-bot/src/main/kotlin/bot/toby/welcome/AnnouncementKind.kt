package bot.toby.welcome

import database.dto.guild.ConfigDto.Configurations

/**
 * Bundles the three [Configurations] keys + default template + embed
 * accent colour + display label for one announcement surface (welcome
 * or goodbye). Adding a third surface in future (e.g. "level-up
 * shoutout", "ban announcement") is a one-case addition here instead of
 * a parallel copy-paste across the handler, the slash command, and the
 * web service.
 *
 * The default template constants live on [WelcomeMessageRenderer] —
 * this enum just references them so there's still one source of truth
 * for the rendered text shown when a guild hasn't overridden the
 * message.
 */
enum class AnnouncementKind(
    val enabledKey: Configurations,
    val channelKey: Configurations,
    val messageKey: Configurations,
    val defaultTemplate: String,
    /** RGB triple for the announcement embed. Welcome = Discord green,
     *  goodbye = Discord red. Matches the visual language of native
     *  Discord join / leave system messages. */
    val embedColor: Int,
    val label: String,
) {
    WELCOME(
        enabledKey = Configurations.WELCOME_ENABLED,
        channelKey = Configurations.WELCOME_CHANNEL,
        messageKey = Configurations.WELCOME_MESSAGE,
        defaultTemplate = WelcomeMessageRenderer.DEFAULT_WELCOME,
        embedColor = 0x57F287,
        label = "Welcome",
    ),
    GOODBYE(
        enabledKey = Configurations.GOODBYE_ENABLED,
        channelKey = Configurations.GOODBYE_CHANNEL,
        messageKey = Configurations.GOODBYE_MESSAGE,
        defaultTemplate = WelcomeMessageRenderer.DEFAULT_GOODBYE,
        embedColor = 0xED4245,
        label = "Goodbye",
    ),
}
