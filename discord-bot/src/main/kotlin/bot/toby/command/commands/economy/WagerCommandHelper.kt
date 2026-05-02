package bot.toby.command.commands.economy

import core.command.Command.Companion.invokeDeleteOnMessageResponse
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import java.awt.Color

/**
 * Shared scaffolding for the Discord casino-minigame commands
 * (`/slots`, `/coinflip`, `/dice`, `/highlow`, `/scratch`). Each one
 * was carrying a copy of:
 *
 *   - three identical color constants (win green, lose grey, error red)
 *   - a private `errorEmbed(message)` that built an EmbedBuilder with
 *     the game's title prefix and the shared error red color
 *   - a private `replyError()` that wrapped `errorEmbed` in
 *     `event.hook.sendMessageEmbeds(...).queue(invokeDeleteOnMessageResponse(...))`
 *   - the same four error-case strings ("Not enough credits…",
 *     "Stake must be between…", etc.) repeated verbatim per game
 *
 * This file pulls all of that into one place. The Win/Lose embeds
 * stay in each command since their bodies are game-specific
 * (reels for slots, dice face for dice, anchor cards for highlow…).
 *
 * Game-side migration is uniform:
 *   - drop the three color constants (use [WagerCommandColors] direct)
 *   - drop the private `errorEmbed` / `replyError` helpers (call
 *     [WagerCommandEmbeds] direct, passing the game's title)
 *   - replace the four failure-case message strings with calls to
 *     [WagerCommandEmbeds.failureEmbed]
 */
internal object WagerCommandColors {
    /** #57F287 — also used by the market chart. */
    val WIN: Color = Color(87, 242, 135)

    /** #a0a0b0 — muted grey for losses. */
    val LOSE: Color = Color(160, 160, 176)

    /** #ED4245 — Discord error red. */
    val ERROR: Color = Color(237, 66, 69)
}

/**
 * Standardised "wager service rejected the play" outcomes — a service-
 * agnostic reflection of the failure variants every casino service
 * sealed type carries (`*.InsufficientCredits`, `*.InsufficientCoinsForTopUp`,
 * `*.InvalidStake`, `*.UnknownUser`).
 *
 * Each command's `replyOutcome` translates its own service outcome's
 * failure case into one of these, then hands it to
 * [WagerCommandEmbeds.failureEmbed] for uniform rendering.
 */
internal sealed interface WagerCommandFailure {
    data class InsufficientCredits(val stake: Long, val have: Long) : WagerCommandFailure
    data class InsufficientCoinsForTopUp(val needed: Long, val have: Long) : WagerCommandFailure
    data class InvalidStake(val min: Long, val max: Long) : WagerCommandFailure
    data object UnknownUser : WagerCommandFailure
}

internal object WagerCommandEmbeds {

    /**
     * `"1.95×"`-style label used wherever a per-game payout multiplier
     * needs to render in button copy or embed prose. Each minigame's
     * embed file used to carry its own copy; centralised here so the
     * format stays consistent across `/highlow`, `/baccarat`, and any
     * future minigame that buttons-up its payout previews.
     */
    fun multiplierLabel(multiplier: Double): String = "%.2f×".format(multiplier)

    /**
     * Build an error embed with [title] and [message]. The title is the
     * game-specific prefix (e.g. "🎰 Slots") so the player can tell at a
     * glance which command produced the error.
     */
    fun errorEmbed(title: String, message: String): MessageEmbed = EmbedBuilder()
        .setTitle(title)
        .setDescription(message)
        .setColor(WagerCommandColors.ERROR)
        .build()

    /**
     * Translate a [WagerCommandFailure] into the canonical message
     * shown for that failure shape across every casino game.
     */
    fun failureMessage(failure: WagerCommandFailure): String = when (failure) {
        is WagerCommandFailure.InsufficientCredits ->
            "Not enough credits. You need ${failure.stake} but only have ${failure.have}."
        is WagerCommandFailure.InsufficientCoinsForTopUp ->
            "Not enough credits, and not enough TOBY to cover. " +
                "Need ${failure.needed} TOBY, you have ${failure.have}."
        is WagerCommandFailure.InvalidStake ->
            "Stake must be between ${failure.min} and ${failure.max} credits."
        WagerCommandFailure.UnknownUser ->
            "No user record yet. Try another TobyBot command first."
    }

    /**
     * Composite of [errorEmbed] + [failureMessage] for the common case
     * where a `when` branch wants to emit a typed failure embed in one
     * call.
     */
    fun failureEmbed(title: String, failure: WagerCommandFailure): MessageEmbed =
        errorEmbed(title, failureMessage(failure))

    /** Send [embed] as the reply to [event] and schedule the delete-after. */
    fun reply(event: SlashCommandInteractionEvent, embed: MessageEmbed, deleteDelay: Int) {
        event.hook.sendMessageEmbeds(embed).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    /**
     * Convenience wrapper around [errorEmbed] + [reply] for early
     * pre-service-call validation errors (e.g. missing option, command
     * used in DM). Passes the title each command uses for its embeds.
     */
    fun replyError(
        event: SlashCommandInteractionEvent,
        title: String,
        message: String,
        deleteDelay: Int
    ) {
        reply(event, errorEmbed(title, message), deleteDelay)
    }
}
