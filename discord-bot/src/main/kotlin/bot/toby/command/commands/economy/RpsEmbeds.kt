package bot.toby.command.commands.economy

import common.rps.RpsEngine
import database.service.RpsService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.MessageEmbed

/**
 * Embed + button rendering for `/rps`.
 *
 * Component IDs are colon-delimited so [DefaultButtonManager] can route
 * by the `"rps"` prefix and this object can parse the rest by index:
 *
 *   `rps:<action>:<sessionId>:<scopedDiscordId>`
 *
 * - `action`: ACCEPT / DECLINE during PENDING; PICK_ROCK / PICK_PAPER /
 *   PICK_SCISSORS / FORFEIT during LIVE.
 * - `scopedDiscordId`: the discord id of the *player whose button this
 *   is*. ACCEPT/DECLINE belong to the opponent; PICK belongs to either
 *   player (each player sees the same buttons but the handler routes
 *   the click to the clicking user). FORFEIT belongs to either player.
 *
 * For PICK and FORFEIT buttons we don't actually need to encode the
 * clicker in the id — the click event already carries the user — so
 * we use `0` as a sentinel. Kept in the format for symmetry with the
 * accept-side encoding so the parser is single-shape.
 */
object RpsEmbeds {

    const val BUTTON_NAME = "rps"

    enum class Action {
        ACCEPT,
        DECLINE,
        PICK_ROCK,
        PICK_PAPER,
        PICK_SCISSORS,
        FORFEIT,
    }

    data class ParsedButtonId(
        val action: Action,
        val sessionId: Long,
        /** 0 when not used (PICK / FORFEIT actions). */
        val scopedDiscordId: Long,
    )

    fun acceptButtonId(sessionId: Long, opponentDiscordId: Long): String =
        encode(Action.ACCEPT, sessionId, opponentDiscordId)

    fun declineButtonId(sessionId: Long, opponentDiscordId: Long): String =
        encode(Action.DECLINE, sessionId, opponentDiscordId)

    fun pickButtonId(sessionId: Long, choice: RpsEngine.Choice): String = encode(
        when (choice) {
            RpsEngine.Choice.ROCK -> Action.PICK_ROCK
            RpsEngine.Choice.PAPER -> Action.PICK_PAPER
            RpsEngine.Choice.SCISSORS -> Action.PICK_SCISSORS
        },
        sessionId, 0L,
    )

    fun forfeitButtonId(sessionId: Long): String = encode(Action.FORFEIT, sessionId, 0L)

    private fun encode(action: Action, sessionId: Long, scopedDiscordId: Long): String =
        listOf(BUTTON_NAME, action.name, sessionId.toString(), scopedDiscordId.toString())
            .joinToString(":")

    fun parseButtonId(componentId: String): ParsedButtonId? {
        val parts = componentId.split(':')
        if (parts.size != 4 || !parts[0].equals(BUTTON_NAME, ignoreCase = true)) return null
        val action = runCatching { Action.valueOf(parts[1]) }.getOrNull() ?: return null
        val sessionId = parts[2].toLongOrNull() ?: return null
        val scopedDiscordId = parts[3].toLongOrNull() ?: return null
        return ParsedButtonId(action, sessionId, scopedDiscordId)
    }

    /**
     * Maps a PICK_* action onto the engine's [RpsEngine.Choice] value.
     * Returns null for non-pick actions so the caller can branch.
     */
    fun choiceFor(action: Action): RpsEngine.Choice? = when (action) {
        Action.PICK_ROCK -> RpsEngine.Choice.ROCK
        Action.PICK_PAPER -> RpsEngine.Choice.PAPER
        Action.PICK_SCISSORS -> RpsEngine.Choice.SCISSORS
        else -> null
    }

    // ---- embeds ----

    fun pendingEmbed(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
    ): MessageEmbed = EmbedBuilder()
        .setTitle("✊ ✋ ✌️  Rock-Paper-Scissors")
        .setDescription(
            "<@${opponentDiscordId}> — <@${initiatorDiscordId}> has challenged you to RPS." +
                stakeLine(stake) +
                "\nAccept to start, or decline to walk away."
        )
        .setColor(0x5B8DEF)
        .build()

    fun pickEmbed(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
        initiatorPicked: Boolean,
        opponentPicked: Boolean,
    ): MessageEmbed = EmbedBuilder()
        .setTitle("✊ ✋ ✌️  Make your pick")
        .setDescription(
            "<@${initiatorDiscordId}> vs <@${opponentDiscordId}>" +
                stakeLine(stake) +
                "\n\nClick one of the three moves below — your choice stays hidden until both players have picked." +
                "\n\n" + pickStatus(initiatorDiscordId, opponentDiscordId, initiatorPicked, opponentPicked)
        )
        .setColor(0x5B8DEF)
        .build()

    fun winEmbed(outcome: RpsService.ResolveOutcome.Win): MessageEmbed = EmbedBuilder()
        .setTitle("🏆 Rock-Paper-Scissors — <@${outcome.winnerDiscordId}> wins!")
        .setDescription(
            "<@${outcome.winnerDiscordId}> picked **${prettyChoice(outcome.winnerChoice)}**" +
                ", <@${outcome.loserDiscordId}> picked **${prettyChoice(outcome.loserChoice)}**." +
                if (outcome.pot > 0) {
                    "\n\nPot: **${outcome.pot}** credits → " +
                        "winner takes **${outcome.pot - outcome.lossTribute}** " +
                        "(jackpot tribute: ${outcome.lossTribute})."
                } else "" +
                    if (outcome.xpGranted > 0) "\n+${outcome.xpGranted} XP for the winner." else ""
        )
        .setColor(0x57F287)
        .build()

    fun drawEmbed(outcome: RpsService.ResolveOutcome.Draw, initiatorDiscordId: Long, opponentDiscordId: Long): MessageEmbed = EmbedBuilder()
        .setTitle("🤝 Rock-Paper-Scissors — draw!")
        .setDescription(
            "<@${initiatorDiscordId}> and <@${opponentDiscordId}> both picked **${prettyChoice(outcome.choice)}**." +
                if (outcome.stake > 0) "\n\nStakes refunded to both players." else ""
        )
        .setColor(0xFEE75C)
        .build()

    fun doubleRefundEmbed(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
    ): MessageEmbed = EmbedBuilder()
        .setTitle("⌛ Rock-Paper-Scissors — both timed out")
        .setDescription(
            "Neither <@${initiatorDiscordId}> nor <@${opponentDiscordId}> picked in time." +
                if (stake > 0) "\n\nStakes refunded to both players." else ""
        )
        .setColor(0xED4245)
        .build()

    fun pendingDeclineEmbed(initiatorDiscordId: Long, opponentDiscordId: Long): MessageEmbed = EmbedBuilder()
        .setTitle("❌ Challenge declined")
        .setDescription("<@${opponentDiscordId}> declined <@${initiatorDiscordId}>'s RPS challenge.")
        .setColor(0xED4245)
        .build()

    fun pendingTimeoutEmbed(initiatorDiscordId: Long, opponentDiscordId: Long): MessageEmbed = EmbedBuilder()
        .setTitle("⌛ Challenge expired")
        .setDescription("<@${opponentDiscordId}> didn't respond to <@${initiatorDiscordId}>'s RPS challenge.")
        .setColor(0xED4245)
        .build()

    fun startErrorEmbed(message: String): MessageEmbed = EmbedBuilder()
        .setTitle("❌ Can't start that match")
        .setDescription(message)
        .setColor(0xED4245)
        .build()

    fun acceptErrorEmbed(message: String): MessageEmbed = EmbedBuilder()
        .setTitle("❌ Can't accept that match")
        .setDescription(message)
        .setColor(0xED4245)
        .build()

    // ---- button rows ----

    fun pendingButtons(sessionId: Long, opponentDiscordId: Long): ActionRow = ActionRow.of(
        Button.success(acceptButtonId(sessionId, opponentDiscordId), "Accept"),
        Button.danger(declineButtonId(sessionId, opponentDiscordId), "Decline"),
    )

    fun pickButtons(sessionId: Long): List<ActionRow> = listOf(
        ActionRow.of(
            Button.primary(pickButtonId(sessionId, RpsEngine.Choice.ROCK), "Rock ✊"),
            Button.primary(pickButtonId(sessionId, RpsEngine.Choice.PAPER), "Paper ✋"),
            Button.primary(pickButtonId(sessionId, RpsEngine.Choice.SCISSORS), "Scissors ✌️"),
        ),
        ActionRow.of(
            Button.of(ButtonStyle.SECONDARY, forfeitButtonId(sessionId), "Forfeit"),
        ),
    )

    // ---- helpers ----

    private fun stakeLine(stake: Long): String =
        if (stake > 0L) "\nStake: **${stake}** credits each (winner takes the pot)." else ""

    private fun pickStatus(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        initiatorPicked: Boolean,
        opponentPicked: Boolean,
    ): String = buildString {
        append("<@${initiatorDiscordId}>: ")
        append(if (initiatorPicked) "✅ picked" else "⏳ waiting")
        append("\n<@${opponentDiscordId}>: ")
        append(if (opponentPicked) "✅ picked" else "⏳ waiting")
    }

    fun prettyChoice(choice: RpsEngine.Choice): String = when (choice) {
        RpsEngine.Choice.ROCK -> "Rock ✊"
        RpsEngine.Choice.PAPER -> "Paper ✋"
        RpsEngine.Choice.SCISSORS -> "Scissors ✌️"
    }
}
