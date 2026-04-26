package bot.toby.button.buttons

import bot.toby.command.commands.economy.PokerEmbeds
import core.button.Button
import core.button.ButtonContext
import database.dto.UserDto
import database.poker.PokerEngine
import database.poker.PokerTable
import database.poker.PokerTableRegistry
import database.service.PokerService
import database.service.PokerService.ActionOutcome
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button as JdaButton
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Resolves a betting action under a `/poker` hand-state embed. The
 * action and table id are encoded in the component ID
 * (`poker:CHECK_CALL:<tableId>` etc.) so the handler is stateless.
 *
 * The PEEK action is a no-op against the engine — it just sends the
 * caller their hole cards in an ephemeral reply, so that everyone at
 * the table can see the table state without leaking each other's
 * cards.
 *
 * Actor enforcement (turn discipline, valid action shape) lives in
 * [PokerEngine.applyAction]; this handler just translates the rejection
 * reasons into a friendly ephemeral message.
 */
@Component
class PokerActionButton @Autowired constructor(
    private val pokerService: PokerService,
    private val tableRegistry: PokerTableRegistry,
) : Button {

    override val name: String get() = PokerEmbeds.BUTTON_NAME
    override val description: String get() = "Bet/check/call/fold/peek under a /poker hand-state embed."

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        val parsed = PokerEmbeds.parseButtonId(event.componentId) ?: run {
            event.reply("Couldn't parse this poker button.").setEphemeral(true).queue()
            return
        }
        val table = tableRegistry.get(parsed.tableId)
        if (table == null || table.guildId != ctx.guild.idLong) {
            event.reply("This poker table no longer exists.").setEphemeral(true).queue()
            return
        }

        if (parsed.action == PokerEmbeds.Action.PEEK) {
            handlePeek(event, table, requestingUserDto.discordId)
            return
        }

        if (table.seats.none { it.discordId == requestingUserDto.discordId }) {
            event.reply("You aren't seated at this table — `/poker join` first.").setEphemeral(true).queue()
            return
        }

        val pokerAction = when (parsed.action) {
            PokerEmbeds.Action.CHECK_CALL -> {
                // Auto-pick check vs. call based on whether anything is owed.
                val seat = table.seats.first { it.discordId == requestingUserDto.discordId }
                if (table.currentBet > seat.committedThisRound) PokerEngine.PokerAction.Call
                else PokerEngine.PokerAction.Check
            }
            PokerEmbeds.Action.RAISE -> PokerEngine.PokerAction.Raise
            PokerEmbeds.Action.FOLD -> PokerEngine.PokerAction.Fold
            PokerEmbeds.Action.PEEK -> return // unreachable, handled above
        }

        event.deferEdit().queue()
        val outcome = pokerService.applyAction(
            discordId = requestingUserDto.discordId,
            guildId = ctx.guild.idLong,
            tableId = parsed.tableId,
            action = pokerAction
        )
        when (outcome) {
            is ActionOutcome.Rejected -> sendError(event, rejectionMessage(outcome.reason))
            ActionOutcome.TableNotFound -> sendError(event, "This poker table no longer exists.")
            is ActionOutcome.HandResolved -> {
                event.message.editMessageEmbeds(PokerEmbeds.resultEmbed(table, outcome.result))
                    .setComponents(emptyList<MessageTopLevelComponent>())
                    .queue()
            }
            is ActionOutcome.StreetAdvanced, ActionOutcome.Continued -> {
                // Re-render the embed in place so everyone watching sees the new state.
                event.message.editMessageEmbeds(PokerEmbeds.handStateEmbed(table))
                    .setComponents(actionRow(parsed.tableId))
                    .queue()
            }
        }
    }

    private fun handlePeek(event: ButtonInteractionEvent, table: PokerTable, discordId: Long) {
        val seat = table.seats.firstOrNull { it.discordId == discordId }
        if (seat == null) {
            event.reply("You aren't seated at this table.").setEphemeral(true).queue()
            return
        }
        event.replyEmbeds(PokerEmbeds.peekEmbed(seat.holeCards)).setEphemeral(true).queue()
    }

    private fun sendError(event: ButtonInteractionEvent, message: String) {
        event.hook.sendMessageEmbeds(PokerEmbeds.errorEmbed(message)).setEphemeral(true).queue()
    }

    private fun actionRow(tableId: Long): List<ActionRow> = listOf(
        ActionRow.of(
            JdaButton.primary(PokerEmbeds.buttonId(PokerEmbeds.Action.CHECK_CALL, tableId), "Check / Call"),
            JdaButton.success(PokerEmbeds.buttonId(PokerEmbeds.Action.RAISE, tableId), "Raise"),
            JdaButton.danger(PokerEmbeds.buttonId(PokerEmbeds.Action.FOLD, tableId), "Fold"),
            JdaButton.secondary(PokerEmbeds.buttonId(PokerEmbeds.Action.PEEK, tableId), "Peek cards"),
        )
    )

    private fun rejectionMessage(reason: PokerEngine.RejectReason): String = when (reason) {
        PokerEngine.RejectReason.NO_HAND_IN_PROGRESS -> "There's no hand in progress on this table."
        PokerEngine.RejectReason.NOT_AT_TABLE -> "You aren't seated at this table."
        PokerEngine.RejectReason.NOT_YOUR_TURN -> "It isn't your turn yet."
        PokerEngine.RejectReason.ILLEGAL_CHECK -> "You can't check — there's a bet to call."
        PokerEngine.RejectReason.ILLEGAL_CALL -> "Nothing to call — try Check or Raise."
        PokerEngine.RejectReason.ILLEGAL_RAISE -> "You can't raise here."
        PokerEngine.RejectReason.RAISE_CAP_REACHED -> "Raise cap reached for this street."
        PokerEngine.RejectReason.INSUFFICIENT_CHIPS_TO_CALL -> "Not enough chips to call — fold or buy more credits."
        PokerEngine.RejectReason.INSUFFICIENT_CHIPS_TO_RAISE -> "Not enough chips to raise — try Call instead."
    }
}
