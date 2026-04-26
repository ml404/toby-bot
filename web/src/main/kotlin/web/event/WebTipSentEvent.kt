package web.event

/**
 * Fired by [web.controller.TipController] right after a successful
 * web-initiated tip. Listened to in `discord-bot` (`WebTipNotifier`),
 * which posts the same embed + ping the slash-command path produces
 * — the slash-command path posts inline to the channel where `/tip`
 * was invoked, while web-initiated tips have no such channel context,
 * so the listener targets the guild's system channel.
 */
data class WebTipSentEvent(
    val guildId: Long,
    val senderDiscordId: Long,
    val recipientDiscordId: Long,
    val amount: Long,
    val note: String?,
    val senderNewBalance: Long,
    val recipientNewBalance: Long,
    val sentTodayAfter: Long,
    val dailyCap: Long
)
