package common.events

/**
 * Lifecycle of a per-user, per-game anti-autoclicker suspicion session.
 *
 * Published via Spring's `ApplicationEventPublisher` from
 * `database.service.CasinoBotSuspicionService` (open/close — streak transitions)
 * and `database.service.CasinoEdgeService` (fire — forced-loss substitution).
 *
 * The discord-bot module owns one listener (`AntiAutoclickNotifier`) that posts
 * a single embed when a session opens, edits it in place on every fire (with
 * a debounce so a saturated burst can't hit per-channel edit rate limits),
 * and finalises the message when the session closes. One session message per
 * `(guildId, discordId, gameKey)` triple.
 */
sealed interface AntiAutoclickEvent {
    val guildId: Long
    val discordId: Long
    val gameKey: String

    /**
     * Streak transitioned from 0 → ≥1 — the heuristic just started suspecting
     * this `(user, guild, gameKey)`. `streak` is the new streak value (1 in
     * practice, but typed as Int to keep the listener general).
     */
    data class SessionOpened(
        override val guildId: Long,
        override val discordId: Long,
        override val gameKey: String,
        val streak: Int,
    ) : AntiAutoclickEvent

    /**
     * `CasinoEdgeService` substituted a fair outcome with a forced loss for
     * this user. `streak` is the streak that drove the substitution; `edgePct`
     * is the effective house-edge percentage that fired (0–50, post-cap).
     */
    data class BiasFired(
        override val guildId: Long,
        override val discordId: Long,
        override val gameKey: String,
        val streak: Int,
        val edgePct: Double,
    ) : AntiAutoclickEvent

    /**
     * Streak transitioned from ≥1 → 0 — the user supplied a non-bot-like
     * signal (different pixel, mouse motion, or null/other-modality input).
     * The notifier finalises the open session message with a summary.
     */
    data class SessionClosed(
        override val guildId: Long,
        override val discordId: Long,
        override val gameKey: String,
    ) : AntiAutoclickEvent
}
