package bot.toby.command.commands.game.pvp

import database.service.pvp.PvpWagerService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.MessageEmbed

/**
 * Cross-game embed + button factories for the PvP mini-games (`/rps`,
 * `/tictactoe`, future `/connect4`). Holds the bits that are identical
 * between the per-game `*Embeds.kt` files — error embeds, pending-decline /
 * pending-timeout embeds (game-name parameterised), the Accept / Decline
 * button row, and the stake-line + start-error description text.
 *
 * Game-specific embeds (the pick/turn rendering, the win embed with
 * winner-choice or board-state fields, the per-game decline embed
 * wording) stay in the per-game `*Embeds.kt`.
 */
object PvpEmbeds {

    /** Standard one-line stake summary appended to challenge / turn embeds. */
    fun stakeLine(stake: Long): String =
        if (stake > 0L) "\nStake: **${stake}** credits each (winner takes the pot)." else ""

    /**
     * Standard "challenge expired" embed shown when the opponent didn't
     * respond before [database.rps.RpsSessionRegistry.pendingTtl] /
     * [database.tictactoe.TicTacToeSessionRegistry.pendingTtl].
     */
    fun pendingTimeoutEmbed(
        gameName: String,
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
    ): MessageEmbed = EmbedBuilder()
        .setTitle("⌛ Challenge expired")
        .setDescription("<@${opponentDiscordId}> didn't respond to <@${initiatorDiscordId}>'s $gameName challenge.")
        .setColor(0xED4245)
        .build()

    /** Standard "challenge declined" embed shown when the opponent hits Decline. */
    fun pendingDeclineEmbed(
        gameName: String,
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
    ): MessageEmbed = EmbedBuilder()
        .setTitle("❌ Challenge declined")
        .setDescription("<@${opponentDiscordId}> declined <@${initiatorDiscordId}>'s $gameName challenge.")
        .setColor(0xED4245)
        .build()

    /** Surfaced when [PvpWagerService.preflightStart] rejects the match before posting. */
    fun startErrorEmbed(message: String): MessageEmbed = EmbedBuilder()
        .setTitle("❌ Can't start that match")
        .setDescription(message)
        .setColor(0xED4245)
        .build()

    /** Surfaced when [PvpWagerService.debitBoth] fails on the accept-button click. */
    fun acceptErrorEmbed(message: String): MessageEmbed = EmbedBuilder()
        .setTitle("❌ Can't accept that match")
        .setDescription(message)
        .setColor(0xED4245)
        .build()

    /**
     * Accept + Decline button row attached to a PENDING challenge embed.
     * Each per-game `*Embeds.kt` provides its own button-id encoders
     * (so the button manager can route by the prefix); this helper just
     * wraps the two buttons in an [ActionRow].
     */
    fun pendingButtons(acceptButtonId: String, declineButtonId: String): ActionRow = ActionRow.of(
        Button.success(acceptButtonId, "Accept"),
        Button.danger(declineButtonId, "Decline"),
    )

    /**
     * Resolve a Discord id into a display-friendly name for use in embed
     * titles. JDA's mention syntax (`<@id>`) only renders to a name when
     * the receiving client has the user cached, so for titles (which
     * Discord shows in embed metadata, not the description body) we
     * prefer the resolved name. Falls back to `Player NNNN` matching the
     * web `MemberLookupHelper.fallbackName` shape.
     */
    fun winnerDisplayName(jda: JDA, guildId: Long, winnerId: Long): String =
        jda.getGuildById(guildId)?.getMemberById(winnerId)?.effectiveName
            ?: runCatching { jda.retrieveUserById(winnerId).complete()?.effectiveName }.getOrNull()
            ?: "Player ${winnerId.toString().takeLast(4)}"

    /** Per-game text rendering of a [PvpWagerService.StartOutcome] rejection. */
    fun describeStartOutcome(outcome: PvpWagerService.StartOutcome): String = when (outcome) {
        is PvpWagerService.StartOutcome.InvalidStake ->
            "Stake must be between ${outcome.min} and ${outcome.max} credits for this server."
        is PvpWagerService.StartOutcome.InvalidOpponent -> when (outcome.reason) {
            PvpWagerService.StartOutcome.InvalidOpponent.Reason.SELF -> "You can't challenge yourself."
            PvpWagerService.StartOutcome.InvalidOpponent.Reason.BOT -> "You can't challenge a bot."
        }
        is PvpWagerService.StartOutcome.InitiatorInsufficient ->
            "You need ${outcome.needed} credits but only have ${outcome.have}."
        is PvpWagerService.StartOutcome.OpponentInsufficient ->
            "Your opponent only has ${outcome.have} credits but the stake requires ${outcome.needed}."
        PvpWagerService.StartOutcome.UnknownInitiator ->
            "We couldn't find your profile — try a credit-earning command first."
        PvpWagerService.StartOutcome.UnknownOpponent ->
            "We couldn't find your opponent's profile — they need to be active in the server first."
        is PvpWagerService.StartOutcome.Ok -> "" // never surfaced — Ok path doesn't render an error embed
    }
}
