package bot.toby.command.commands.economy

import database.dto.PokerHandLogDto
import database.poker.Card
import database.poker.PokerTable
import database.poker.PokerTable.Phase
import database.service.PokerService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Shared embed/component plumbing for the Discord `/poker` flow.
 *
 * Component IDs encode the action and table id:
 *   `poker:CHECK_CALL:<tableId>`
 * The `bot.toby.button.buttons.PokerActionButton` handler parses this
 * back out and routes to [PokerService.applyAction]. The "peek" button
 * has no game effect — it just sends the clicker an ephemeral list of
 * their hole cards.
 */
internal object PokerEmbeds {

    const val BUTTON_NAME = "poker"
    private const val BUTTON_DELIM = ":"

    private val TABLE_COLOR = Color(0x2C, 0x3E, 0x50)
    private val WIN_COLOR = Color(0x57, 0xF2, 0x87)
    private val NEUTRAL_COLOR = Color(0xA0, 0xA0, 0xB0)
    private val ERROR_COLOR = Color(0xED, 0x42, 0x45)

    enum class Action { CHECK_CALL, RAISE, FOLD, PEEK }

    fun buttonId(action: Action, tableId: Long): String =
        listOf(BUTTON_NAME, action.name, tableId.toString()).joinToString(BUTTON_DELIM)

    data class ParsedButtonId(val action: Action, val tableId: Long)

    fun parseButtonId(componentId: String): ParsedButtonId? {
        val parts = componentId.split(BUTTON_DELIM)
        if (parts.size != 3 || !parts[0].equals(BUTTON_NAME, ignoreCase = true)) return null
        val action = runCatching { Action.valueOf(parts[1].uppercase()) }.getOrNull() ?: return null
        val tableId = parts[2].toLongOrNull() ?: return null
        return ParsedButtonId(action, tableId)
    }

    fun lobbyEmbed(table: PokerTable): MessageEmbed = EmbedBuilder()
        .setTitle("🃏 Poker table #${table.id}")
        .setDescription(
            "Fixed-limit Texas Hold'em. Buy-in **${table.minBuyIn}–${table.maxBuyIn}** credits. " +
                "Blinds **${table.smallBlind}/${table.bigBlind}**, bets **${table.smallBet}/${table.bigBet}** " +
                "(pre-flop & flop / turn & river)."
        )
        .addField("Players", playerList(table), false)
        .addField(
            "How to join",
            "Run `/poker join table:${table.id} chips:<amount>`. " +
                "Host (<@${table.hostDiscordId}>) starts the hand with `/poker start table:${table.id}`.",
            false
        )
        .setFooter("Side pots split the pot when players go all-in for different amounts.")
        .setColor(TABLE_COLOR)
        .build()

    fun handStateEmbed(table: PokerTable): MessageEmbed {
        val community = if (table.community.isEmpty()) "—" else table.community.joinToString(" ") { "`$it`" }
        val actor = table.seats.getOrNull(table.actorIndex)
        val toAct = actor?.let { "<@${it.discordId}>" } ?: "—"
        return EmbedBuilder()
            .setTitle("🃏 Hand #${table.handNumber} • ${phaseLabel(table.phase)}")
            .setDescription("Pot: **${table.pot}** • Bet to call: **${table.currentBet}**")
            .addField("Community", community, false)
            .addField("Seats", seatList(table), false)
            .addField("To act", toAct, false)
            .setColor(TABLE_COLOR)
            .setFooter("Click Check/Call, Raise, or Fold below. Peek to see your hole cards.")
            .build()
    }

    fun resultEmbed(table: PokerTable, result: PokerTable.HandResult): MessageEmbed {
        val board = if (result.board.isEmpty()) "—" else result.board.joinToString(" ") { "`$it`" }
        val winners = result.winners.joinToString(", ") { "<@$it>" }
        val payouts = result.payoutByDiscordId.entries.joinToString(", ") { (id, amt) -> "<@$id>: +$amt" }
        val reveals = if (result.revealedHoleCards.isEmpty()) {
            "Everyone else folded — winner takes pot uncontested."
        } else {
            result.revealedHoleCards.entries.joinToString("\n") { (id, cards) ->
                "<@$id>: ${cards.joinToString(" ") { "`$it`" }}"
            }
        }
        return EmbedBuilder()
            .setTitle("🃏 Hand #${result.handNumber} settled")
            .setDescription("Winners: $winners")
            .addField("Pot", "${result.pot} (rake ${result.rake} → jackpot)", true)
            .addField("Payouts", payouts.ifEmpty { "—" }, true)
            .addField("Board", board, false)
            .addField("Showdown", reveals, false)
            .addField("Stacks", seatList(table, includeStatus = false), false)
            .setColor(WIN_COLOR)
            .setFooter("Host: /poker start to deal the next hand. /poker leave to cash out.")
            .build()
    }

    fun peekEmbed(holeCards: List<Card>): MessageEmbed = EmbedBuilder()
        .setTitle("🃏 Your hole cards")
        .setDescription(if (holeCards.isEmpty()) "You haven't been dealt in yet." else holeCards.joinToString(" ") { "`$it`" })
        .setColor(NEUTRAL_COLOR)
        .build()

    fun errorEmbed(message: String): MessageEmbed = EmbedBuilder()
        .setTitle("🃏 Poker")
        .setDescription(message)
        .setColor(ERROR_COLOR)
        .build()

    /**
     * v2-3: render up to a screenful of recent settled hands. [scope] is
     * the title-friendly label (e.g. "Table #7" or "Server"); [hands] are
     * the rows in the order they should be rendered (caller is expected
     * to pass them newest-first to match the JPA query). Returns a
     * compact summary if the list is empty rather than an empty embed.
     */
    fun historyEmbed(scope: String, hands: List<PokerHandLogDto>): MessageEmbed {
        val builder = EmbedBuilder()
            .setTitle("🃏 Recent hands • $scope")
            .setColor(NEUTRAL_COLOR)
        if (hands.isEmpty()) {
            return builder.setDescription("No settled hands yet.").build()
        }
        val lines = hands.map { row ->
            val winners = row.winners.split(",").filter { it.isNotBlank() }
                .joinToString(", ") { "<@$it>" }
                .ifBlank { "—" }
            val board = if (row.board.isBlank()) "—" else row.board.replace(",", " ")
            val ts = row.resolvedAt?.let { HISTORY_TIME_FMT.format(it) } ?: "—"
            "`#${row.handNumber}` `${row.tableId}` • pot **${row.pot}** (rake ${row.rake}) • $winners • $board • _${ts}_"
        }
        return builder.setDescription(lines.joinToString("\n")).build()
    }

    private val HISTORY_TIME_FMT: DateTimeFormatter = DateTimeFormatter
        .ofPattern("MMM d HH:mm")
        .withZone(ZoneOffset.UTC)

    fun infoEmbed(message: String): MessageEmbed = EmbedBuilder()
        .setTitle("🃏 Poker")
        .setDescription(message)
        .setColor(NEUTRAL_COLOR)
        .build()

    private fun playerList(table: PokerTable): String =
        if (table.seats.isEmpty()) "—" else table.seats.joinToString("\n") { "• <@${it.discordId}> (${it.chips} chips)" }

    private fun seatList(table: PokerTable, includeStatus: Boolean = true): String =
        if (table.seats.isEmpty()) "—" else table.seats.mapIndexed { i, s ->
            val markers = buildList {
                if (i == table.dealerIndex) add("D")
                if (i == table.actorIndex && table.phase != Phase.WAITING) add("●")
                if (includeStatus && s.status != PokerTable.SeatStatus.SITTING_OUT) add(s.status.name)
            }.joinToString(" ")
            val tag = if (markers.isBlank()) "" else " [$markers]"
            "• <@${s.discordId}> — ${s.chips} chips$tag"
        }.joinToString("\n")

    private fun phaseLabel(phase: Phase): String = when (phase) {
        Phase.WAITING -> "Waiting"
        Phase.PRE_FLOP -> "Pre-flop"
        Phase.FLOP -> "Flop"
        Phase.TURN -> "Turn"
        Phase.RIVER -> "River"
    }
}
