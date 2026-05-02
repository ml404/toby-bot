package bot.toby.command.commands.economy

import database.blackjack.Blackjack
import database.blackjack.BlackjackTable
import database.blackjack.bestTotal
import database.blackjack.isSoft
import database.card.Card
import database.dto.BlackjackHandLogDto
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Shared embed/component plumbing for the Discord `/blackjack` flow.
 *
 * Component IDs encode the action and table id:
 *   `blackjack:HIT:<tableId>`
 * The [bot.toby.button.buttons.BlackjackButton] handler parses this back
 * out and routes to either [database.service.BlackjackService.applySoloAction]
 * or [database.service.BlackjackService.applyMultiAction] depending on
 * the table's [BlackjackTable.Mode].
 */
internal object BlackjackEmbeds {

    const val BUTTON_NAME = "blackjack"
    private const val BUTTON_DELIM = ":"
    private const val TITLE = "🂡 Blackjack"

    private val TABLE_COLOR = Color(0x2C, 0x3E, 0x50)
    private val NEUTRAL_COLOR = Color(0xA0, 0xA0, 0xB0)
    private const val HIDDEN = "🂠"

    enum class Action { HIT, STAND, DOUBLE, SPLIT, PEEK }

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

    private fun cardLabel(card: Card): String = "`$card`"

    /** Render a hand inline: `K♠` `7♦` (17). Empty hand renders as a dash. */
    fun handLine(hand: List<Card>): String {
        if (hand.isEmpty()) return "—"
        val total = bestTotal(hand)
        val soft = if (isSoft(hand) && total < 21) " soft" else ""
        return hand.joinToString(" ") { cardLabel(it) } + " **($total$soft)**"
    }

    /**
     * Dealer line during PLAYER_TURNS: show the up-card and a hidden
     * hole card. After the dealer plays out, callers should use
     * [handLine] instead.
     */
    fun dealerUpLine(dealer: List<Card>): String {
        if (dealer.isEmpty()) return "—"
        val up = dealer.first()
        return "${cardLabel(up)} $HIDDEN"
    }

    // --- SOLO ---

    fun soloDealEmbed(table: BlackjackTable): MessageEmbed {
        val seat = table.seats.firstOrNull()
        val playerHand = seat?.hand.orEmpty()
        val title = "$TITLE • ${seat?.stake ?: 0L} credits at risk"
        val description = buildString {
            append("**You:** ").append(handLine(playerHand)).append('\n')
            append("**Dealer:** ").append(dealerUpLine(table.dealer))
        }
        return EmbedBuilder()
            .setTitle(title)
            .setDescription(description)
            .setColor(TABLE_COLOR)
            .setFooter("Hit, Stand, or Double Down. Reaching 21 auto-stands.")
            .build()
    }

    fun soloResolvedEmbed(
        table: BlackjackTable,
        result: BlackjackTable.HandResult,
        newBalance: Long,
        jackpotPayout: Long,
        lossTribute: Long
    ): MessageEmbed {
        val seat = table.seats.firstOrNull()
        val playerHand = seat?.hand.orEmpty()
        val playerResult = result.seatResults.values.firstOrNull() ?: Blackjack.Result.PUSH
        val payout = result.payouts.values.firstOrNull() ?: 0L
        val stake = seat?.stake ?: result.pot
        val net = payout - stake
        val verdict = when (playerResult) {
            Blackjack.Result.PLAYER_BLACKJACK -> "Blackjack! +$net credits (3:2 payout)."
            Blackjack.Result.PLAYER_WIN -> "You win! +$net credits."
            Blackjack.Result.PUSH -> "Push — your $stake credits come back."
            Blackjack.Result.DEALER_WIN -> "Dealer wins. -$stake credits."
            Blackjack.Result.PLAYER_BUST -> "Bust. -$stake credits."
        }
        val description = buildString {
            append("**You:** ").append(handLine(playerHand)).append('\n')
            append("**Dealer:** ").append(handLine(result.dealer)).append('\n')
            append('\n').append(verdict)
            if (jackpotPayout > 0L) append("\n🎰 Jackpot hit! +$jackpotPayout credits.")
            if (lossTribute > 0L) append("\n💰 +$lossTribute credits to the jackpot pool.")
        }
        val color = when (playerResult) {
            Blackjack.Result.PLAYER_BLACKJACK, Blackjack.Result.PLAYER_WIN -> WagerCommandColors.WIN
            Blackjack.Result.PUSH -> NEUTRAL_COLOR
            Blackjack.Result.DEALER_WIN, Blackjack.Result.PLAYER_BUST -> WagerCommandColors.LOSE
        }
        return EmbedBuilder()
            .setTitle(TITLE)
            .setDescription(description)
            .addField("New balance", "$newBalance credits", true)
            .setColor(color)
            .build()
    }

    // --- MULTI ---

    fun lobbyEmbed(table: BlackjackTable): MessageEmbed {
        val seats = if (table.seats.isEmpty()) "—" else table.seats.joinToString("\n") { "• <@${it.discordId}>" }
        return EmbedBuilder()
            .setTitle("$TITLE • Table #${table.id}")
            .setDescription(
                "Multiplayer (players vs. shared dealer). Ante **${table.ante}** credits per hand. " +
                    "Up to ${table.maxSeats} seats."
            )
            .addField("Seated", seats, false)
            .addField(
                "How to join",
                "`/blackjack join table:${table.id}` — your ante is debited immediately. " +
                    "Host (<@${table.hostDiscordId}>) starts the hand with `/blackjack start table:${table.id}`.",
                false
            )
            .setFooter("Best non-bust beats the dealer. Pot splits among winners; ${(Blackjack.MULTI_RAKE * 100).toInt()}% rake to the jackpot pool.")
            .setColor(TABLE_COLOR)
            .build()
    }

    fun multiHandStateEmbed(table: BlackjackTable): MessageEmbed {
        val description = buildString {
            append("**Dealer:** ").append(dealerUpLine(table.dealer)).append("\n\n")
            for ((i, seat) in table.seats.withIndex()) {
                val arrow = if (i == table.actorIndex && table.phase == BlackjackTable.Phase.PLAYER_TURNS) "▶ " else "  "
                val statusBadge = when (seat.status) {
                    BlackjackTable.SeatStatus.BLACKJACK -> " — **Blackjack!**"
                    BlackjackTable.SeatStatus.BUSTED -> " — **Bust**"
                    BlackjackTable.SeatStatus.STANDING -> " — Stand"
                    BlackjackTable.SeatStatus.DOUBLED -> " — Doubled"
                    BlackjackTable.SeatStatus.ACTIVE -> ""
                }
                append(arrow).append("<@${seat.discordId}>: ")
                    .append(handLine(seat.hand))
                    .append(statusBadge)
                    .append('\n')
            }
            val actor = table.seats.getOrNull(table.actorIndex)
            if (table.phase == BlackjackTable.Phase.PLAYER_TURNS && actor != null) {
                append("\nTo act: <@${actor.discordId}>")
            }
        }
        return EmbedBuilder()
            .setTitle("$TITLE • Table #${table.id} • Hand #${table.handNumber}")
            .setDescription(description)
            .setFooter("Click Hit, Stand, or Double on your turn. Peek to see your cards.")
            .setColor(TABLE_COLOR)
            .build()
    }

    fun multiResolvedEmbed(table: BlackjackTable, result: BlackjackTable.HandResult): MessageEmbed {
        val description = buildString {
            append("**Dealer:** ").append(handLine(result.dealer)).append("\n\n")
            for ((id, r) in result.seatResults) {
                val payout = result.payouts[id] ?: 0L
                val verdict = when (r) {
                    Blackjack.Result.PLAYER_BLACKJACK -> "🎉 Blackjack — +$payout"
                    Blackjack.Result.PLAYER_WIN -> "✅ Win — +$payout"
                    Blackjack.Result.PUSH -> "🤝 Push — refunded $payout"
                    Blackjack.Result.DEALER_WIN -> "❌ Lose"
                    Blackjack.Result.PLAYER_BUST -> "💥 Bust"
                }
                append("<@$id>: ").append(verdict).append('\n')
            }
            append("\nPot **${result.pot}** • rake **${result.rake}** → jackpot")
        }
        return EmbedBuilder()
            .setTitle("$TITLE • Table #${table.id} • Hand #${result.handNumber} settled")
            .setDescription(description)
            .setFooter("Re-join with /blackjack join to play another hand.")
            .setColor(WagerCommandColors.WIN)
            .build()
    }

    fun peekEmbed(hand: List<Card>): MessageEmbed = EmbedBuilder()
        .setTitle("$TITLE • Your hand")
        .setDescription(if (hand.isEmpty()) "You haven't been dealt yet." else handLine(hand))
        .setColor(NEUTRAL_COLOR)
        .build()

    fun infoEmbed(message: String): MessageEmbed = EmbedBuilder()
        .setTitle(TITLE)
        .setDescription(message)
        .setColor(NEUTRAL_COLOR)
        .build()

    fun errorEmbed(message: String): MessageEmbed = WagerCommandEmbeds.errorEmbed(TITLE, message)

    /**
     * Render up to a screenful of recent settled hands, mirroring
     * `PokerEmbeds.historyEmbed`. [scope] is the title-friendly label
     * ("Server" or "Table #7"); [hands] are the rows in the order they
     * should be rendered (caller passes them newest-first to match the
     * JPA query). Falls back to a compact "no hands yet" line if empty.
     */
    fun historyEmbed(scope: String, hands: List<BlackjackHandLogDto>): MessageEmbed {
        val builder = EmbedBuilder()
            .setTitle("$TITLE • Recent hands • $scope")
            .setColor(NEUTRAL_COLOR)
        if (hands.isEmpty()) {
            return builder.setDescription("No settled hands yet.").build()
        }
        val lines = hands.map { row ->
            val dealer = if (row.dealer.isBlank()) "—" else row.dealer.replace(",", " ")
            val results = row.seatResults.split(",").filter { it.isNotBlank() }.joinToString(" · ") { entry ->
                val parts = entry.split(":", limit = 2)
                if (parts.size == 2) "<@${parts[0]}> ${shortResult(parts[1])}" else entry
            }
            val ts = row.resolvedAt.let { HISTORY_TIME_FMT.format(it) }
            "`#${row.handNumber}` `${row.tableId}` ${row.mode} • dealer $dealer (${row.dealerTotal}) • $results • pot **${row.pot}** (rake ${row.rake}) • _${ts}_"
        }
        return builder.setDescription(lines.joinToString("\n")).build()
    }

    private fun shortResult(name: String): String = when (name) {
        "PLAYER_BLACKJACK" -> "🎉BJ"
        "PLAYER_WIN" -> "✅"
        "PUSH" -> "🤝"
        "DEALER_WIN" -> "❌"
        "PLAYER_BUST" -> "💥"
        else -> name
    }

    private val HISTORY_TIME_FMT: DateTimeFormatter = DateTimeFormatter
        .ofPattern("MMM d HH:mm")
        .withZone(ZoneOffset.UTC)
}
