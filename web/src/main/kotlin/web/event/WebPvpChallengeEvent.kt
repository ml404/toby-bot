package web.event

/**
 * Published when a PvP challenge (rock-paper-scissors, tic-tac-toe,
 * connect 4) is created from the web UI or the Discord Activity.
 * Mirrors [WebDuelOfferedEvent]'s role for the duel flow: web-initiated
 * challenges have no slash-command channel context, so the discord-bot
 * module listens for this and posts a channel notification (preferring
 * the participants' current voice channel — that's where an
 * activity-launched challenge's audience is looking).
 *
 * Unlike duels, these games have no Discord-side accept button — the
 * notification is a pointer back into the casino (web or activity)
 * where the offer can be accepted.
 */
data class WebPvpChallengeEvent(
    val guildId: Long,
    /** Human-readable game name, e.g. "rock-paper-scissors". */
    val gameLabel: String,
    val initiatorDiscordId: Long,
    val opponentDiscordId: Long,
    val stake: Long,
)
