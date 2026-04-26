package bot.toby.command.commands.economy

import database.duel.PendingDuelRegistry
import database.service.DuelService.AcceptOutcome
import database.service.DuelService.StartOutcome
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color
import java.time.Duration

/**
 * Shared embed/component plumbing for the Discord `/duel` flow.
 * Component IDs encode the action, duel id, and the opponent's discord
 * id so the button handler can both verify the clicker and look up
 * the pending offer in [database.duel.PendingDuelRegistry] without
 * any extra round-trip.
 */
internal object DuelEmbeds {

    const val BUTTON_NAME = "duel"
    private const val BUTTON_DELIM = ":"

    private val OFFER_COLOR = Color(88, 101, 242)
    private val WIN_COLOR = Color(87, 242, 135)
    private val NEUTRAL_COLOR = Color(160, 160, 176)
    private val ERROR_COLOR = Color(237, 66, 69)

    enum class Action { ACCEPT, DECLINE }

    fun acceptButtonId(duelId: Long, opponentDiscordId: Long): String =
        encodeButtonId(Action.ACCEPT, duelId, opponentDiscordId)

    fun declineButtonId(duelId: Long, opponentDiscordId: Long): String =
        encodeButtonId(Action.DECLINE, duelId, opponentDiscordId)

    private fun encodeButtonId(action: Action, duelId: Long, opponentDiscordId: Long): String =
        listOf(BUTTON_NAME, action.name, duelId.toString(), opponentDiscordId.toString())
            .joinToString(BUTTON_DELIM)

    data class ParsedButtonId(
        val action: Action,
        val duelId: Long,
        val opponentDiscordId: Long
    )

    fun parseButtonId(componentId: String): ParsedButtonId? {
        val parts = componentId.split(BUTTON_DELIM)
        if (parts.size != 4 || !parts[0].equals(BUTTON_NAME, ignoreCase = true)) return null
        val action = runCatching { Action.valueOf(parts[1].uppercase()) }.getOrNull() ?: return null
        val duelId = parts[2].toLongOrNull() ?: return null
        val opponentDiscordId = parts[3].toLongOrNull() ?: return null
        return ParsedButtonId(action, duelId, opponentDiscordId)
    }

    fun offerEmbed(
        initiatorDiscordId: Long,
        opponentDiscordId: Long,
        stake: Long,
        ttl: Duration
    ): MessageEmbed = EmbedBuilder()
        .setTitle("⚔️ Duel offered")
        .setDescription(
            "<@$initiatorDiscordId> challenges <@$opponentDiscordId> to a duel for **$stake credits** each. " +
                "Winner takes the pot (minus a small jackpot tribute). " +
                "Accept within ${PendingDuelRegistry.formatTtl(ttl)}."
        )
        .setColor(OFFER_COLOR)
        .build()

    fun winEmbed(outcome: AcceptOutcome.Win): MessageEmbed = EmbedBuilder()
        .setTitle("⚔️ Duel resolved")
        .setDescription(
            "<@${outcome.winnerDiscordId}> beat <@${outcome.loserDiscordId}> for **+${outcome.stake - outcome.lossTribute} credits** " +
                "(pot ${outcome.pot}, jackpot tribute ${outcome.lossTribute})."
        )
        .addField("Winner balance", "${outcome.winnerNewBalance} credits", true)
        .addField("Loser balance", "${outcome.loserNewBalance} credits", true)
        .setColor(WIN_COLOR)
        .build()

    fun declineEmbed(initiatorDiscordId: Long, opponentDiscordId: Long, stake: Long): MessageEmbed = EmbedBuilder()
        .setTitle("⚔️ Duel declined")
        .setDescription(
            "<@$opponentDiscordId> declined the **$stake credit** challenge from <@$initiatorDiscordId>. " +
                "No credits moved."
        )
        .setColor(NEUTRAL_COLOR)
        .build()

    fun timeoutEmbed(initiatorDiscordId: Long, opponentDiscordId: Long, stake: Long): MessageEmbed = EmbedBuilder()
        .setTitle("⚔️ Duel offer expired")
        .setDescription(
            "<@$opponentDiscordId> didn't respond to <@$initiatorDiscordId>'s **$stake credit** challenge in time."
        )
        .setColor(NEUTRAL_COLOR)
        .build()

    fun startErrorEmbed(outcome: StartOutcome): MessageEmbed = errorEmbed(
        when (outcome) {
            is StartOutcome.InvalidStake -> "Stake must be between ${outcome.min} and ${outcome.max} credits."
            is StartOutcome.InvalidOpponent -> when (outcome.reason) {
                StartOutcome.InvalidOpponent.Reason.SELF -> "You can't duel yourself."
                StartOutcome.InvalidOpponent.Reason.BOT -> "You can't duel a bot."
            }
            is StartOutcome.InitiatorInsufficient ->
                "You need ${outcome.needed} credits to duel, but only have ${outcome.have}."
            is StartOutcome.OpponentInsufficient ->
                "Opponent only has ${outcome.have} credits — they can't cover a ${outcome.needed} stake."
            StartOutcome.UnknownInitiator -> "No user record yet. Try another TobyBot command first."
            StartOutcome.UnknownOpponent -> "Opponent has no user record in this guild yet."
            is StartOutcome.Ok -> "OK" // unreachable
        }
    )

    fun acceptErrorEmbed(outcome: AcceptOutcome): MessageEmbed = errorEmbed(
        when (outcome) {
            is AcceptOutcome.InitiatorInsufficient ->
                "Challenger no longer has enough credits — they have ${outcome.have} but need ${outcome.needed}."
            is AcceptOutcome.OpponentInsufficient ->
                "You no longer have enough credits — you have ${outcome.have} but need ${outcome.needed}."
            AcceptOutcome.UnknownInitiator -> "Challenger's user record vanished."
            AcceptOutcome.UnknownOpponent -> "Your user record vanished."
            is AcceptOutcome.Win -> "OK" // unreachable
        }
    )

    fun errorEmbed(message: String): MessageEmbed = EmbedBuilder()
        .setTitle("⚔️ Duel")
        .setDescription(message)
        .setColor(ERROR_COLOR)
        .build()
}
