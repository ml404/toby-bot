package web.event

/**
 * Fired by [web.controller.DuelController] right after a successful
 * web-initiated duel challenge. Listened to in `discord-bot`
 * (`WebDuelOfferNotifier`), which posts the same embed + ping +
 * Accept/Decline buttons the slash-command path produces. The
 * slash-command path posts inline to the channel where `/duel` was
 * invoked; web-initiated offers have no such channel context, so the
 * listener targets the guild's system channel.
 */
data class WebDuelOfferedEvent(
    val guildId: Long,
    val duelId: Long,
    val initiatorDiscordId: Long,
    val opponentDiscordId: Long,
    val stake: Long
)
