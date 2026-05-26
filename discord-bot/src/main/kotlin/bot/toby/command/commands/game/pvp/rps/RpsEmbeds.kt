package bot.toby.command.commands.game.pvp.rps

import common.pvp.rps.RpsEngine
import database.service.pvp.rps.RpsService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.MessageEmbed
import bot.toby.command.commands.game.pvp.PvpButtonIdCodec
import bot.toby.command.commands.game.pvp.PvpEmbeds

/**
 * Embed + button rendering specific to `/rps`. The cross-game bits
 * (Accept/Decline button row, stake-line text, error / decline /
 * timeout embeds, start-error description) live in [PvpEmbeds] —
 * this file only owns the RPS-specific pick buttons + pick/win/draw
 * embeds.
 *
 * Component IDs are colon-delimited so [DefaultButtonManager] can route
 * by the `"rps"` prefix and this object can parse the rest by index:
 *
 *   `rps:<action>:<sessionId>:<scopedDiscordId>`
 *
 * - `action`: ACCEPT / DECLINE during PENDING; PICK_ROCK / PICK_PAPER /
 *   PICK_SCISSORS / FORFEIT during LIVE.
 * - `scopedDiscordId`: the discord id of the *player whose button this
 *   is*. ACCEPT/DECLINE belong to the opponent; PICK/FORFEIT use `0`
 *   as a sentinel (clicker is known from the event).
 */
object RpsEmbeds {

    const val BUTTON_NAME = "rps"
    private const val GAME_NAME = "RPS"

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
        PvpButtonIdCodec.encode(BUTTON_NAME, action.name, sessionId, scopedDiscordId)

    fun parseButtonId(componentId: String): ParsedButtonId? {
        val raw = PvpButtonIdCodec.parse(componentId, BUTTON_NAME) ?: return null
        val action = runCatching { Action.valueOf(raw.actionName) }.getOrNull() ?: return null
        return ParsedButtonId(action, raw.sessionId, raw.payload)
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

    // ---- RPS-specific embeds ----

    fun pendingEmbed(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
    ): MessageEmbed = EmbedBuilder()
        .setTitle("✊ ✋ ✌️  Rock-Paper-Scissors")
        .setDescription(
            "<@${opponentDiscordId}> — <@${initiatorDiscordId}> has challenged you to RPS." +
                PvpEmbeds.stakeLine(stake) +
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
                PvpEmbeds.stakeLine(stake) +
                "\n\nClick one of the three moves below — your choice stays hidden until both players have picked." +
                "\n\n" + pickStatus(initiatorDiscordId, opponentDiscordId, initiatorPicked, opponentPicked)
        )
        .setColor(0x5B8DEF)
        .build()

    fun winEmbed(outcome: RpsService.ResolveOutcome.Win, winnerName: String): MessageEmbed = EmbedBuilder()
        .setTitle("🏆 Rock-Paper-Scissors — $winnerName wins!")
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

    // ---- shared-embed pass-throughs (game-name pre-filled) ----

    fun pendingDeclineEmbed(initiatorDiscordId: Long, opponentDiscordId: Long): MessageEmbed =
        PvpEmbeds.pendingDeclineEmbed(GAME_NAME, initiatorDiscordId, opponentDiscordId)

    fun pendingTimeoutEmbed(initiatorDiscordId: Long, opponentDiscordId: Long): MessageEmbed =
        PvpEmbeds.pendingTimeoutEmbed(GAME_NAME, initiatorDiscordId, opponentDiscordId)

    // ---- button rows ----

    fun pendingButtons(sessionId: Long, opponentDiscordId: Long): ActionRow =
        PvpEmbeds.pendingButtons(
            acceptButtonId = acceptButtonId(sessionId, opponentDiscordId),
            declineButtonId = declineButtonId(sessionId, opponentDiscordId),
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
