package bot.toby.button.buttons

import bot.toby.command.commands.economy.BlackjackEmbeds
import core.button.Button
import core.button.ButtonContext
import database.blackjack.Blackjack
import database.blackjack.BlackjackTable
import database.blackjack.BlackjackTableRegistry
import database.blackjack.canSplit
import database.dto.UserDto
import database.service.BlackjackService
import database.service.BlackjackService.MultiActionOutcome
import database.service.BlackjackService.SoloActionOutcome
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button as JdaButton
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Resolves a click on a `/blackjack` action button. The button id
 * encodes only the action and table id (`blackjack:HIT:<tableId>`); the
 * actual hand state lives in the [BlackjackTableRegistry], which the
 * service mutates under a per-table monitor.
 *
 * Solo: only the seated player can act — anyone else gets an ephemeral
 * "not your hand" reply.
 *
 * Multi: turn discipline is enforced inside
 * [database.service.BlackjackService.applyMultiAction], and rejections
 * (NotYourTurn, IllegalAction, etc.) come back as ephemeral messages so
 * the shared embed isn't disrupted for everyone else.
 */
@Component
class BlackjackButton @Autowired constructor(
    private val blackjackService: BlackjackService,
    private val tableRegistry: BlackjackTableRegistry,
) : Button {

    override val name: String get() = BlackjackEmbeds.BUTTON_NAME
    override val description: String get() = "Hit/Stand/Double under a /blackjack hand embed."

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        val parsed = BlackjackEmbeds.parseButtonId(event.componentId) ?: run {
            event.reply("Couldn't parse this blackjack button.").setEphemeral(true).queue()
            return
        }
        val table = tableRegistry.get(parsed.tableId)
        if (table == null || table.guildId != ctx.guild.idLong) {
            event.reply("This blackjack hand no longer exists.").setEphemeral(true).queue()
            return
        }

        if (parsed.action == BlackjackEmbeds.Action.PEEK) {
            handlePeek(event, table, requestingUserDto.discordId)
            return
        }

        when (table.mode) {
            BlackjackTable.Mode.SOLO -> handleSolo(event, table, parsed, requestingUserDto.discordId)
            BlackjackTable.Mode.MULTI -> handleMulti(event, table, parsed, requestingUserDto.discordId)
        }
    }

    private fun handleSolo(
        event: ButtonInteractionEvent,
        table: BlackjackTable,
        parsed: BlackjackEmbeds.ParsedButtonId,
        discordId: Long
    ) {
        val seat = table.seats.firstOrNull()
        if (seat == null || seat.discordId != discordId) {
            event.reply("This isn't your hand — run `/blackjack solo` to start your own.")
                .setEphemeral(true).queue()
            return
        }
        val action = mapAction(parsed.action) ?: return
        event.deferEdit().queue()
        when (val outcome = blackjackService.applySoloAction(discordId, table.guildId, table.id, action)) {
            is SoloActionOutcome.Continued -> {
                val live = tableRegistry.get(table.id) ?: return
                val liveSeat = live.seats.firstOrNull()
                val active = liveSeat?.activeHand
                val allowDouble = active != null && active.cards.size == 2 && !active.doubled
                val allowSplit = active != null &&
                    !active.doubled &&
                    canSplit(active.cards) &&
                    liveSeat.hands.size < Blackjack.MAX_SPLIT_HANDS
                event.message.editMessageEmbeds(BlackjackEmbeds.soloDealEmbed(live))
                    .setComponents(soloActionRow(table.id, allowDouble, allowSplit))
                    .queue()
            }
            is SoloActionOutcome.Resolved -> {
                event.message.editMessageEmbeds(
                    BlackjackEmbeds.soloResolvedEmbed(
                        table, outcome.result, outcome.newBalance, outcome.jackpotPayout, outcome.lossTribute
                    )
                ).setComponents(emptyList<MessageTopLevelComponent>()).queue()
                blackjackService.closeSoloTable(table.id)
            }
            SoloActionOutcome.HandNotFound -> sendError(event, "This blackjack hand no longer exists.")
            SoloActionOutcome.NotYourHand -> sendError(event, "This isn't your hand.")
            SoloActionOutcome.IllegalAction -> sendError(event, "You can't do that right now.")
            is SoloActionOutcome.InsufficientCreditsForDouble -> sendError(
                event, "Need ${outcome.needed} credits to double — you only have ${outcome.have}."
            )
            is SoloActionOutcome.InsufficientCreditsForSplit -> sendError(
                event, "Need ${outcome.needed} credits to split — you only have ${outcome.have}."
            )
        }
    }

    private fun handleMulti(
        event: ButtonInteractionEvent,
        table: BlackjackTable,
        parsed: BlackjackEmbeds.ParsedButtonId,
        discordId: Long
    ) {
        if (table.seats.none { it.discordId == discordId }) {
            event.reply("You aren't seated at this table — `/blackjack join` first.")
                .setEphemeral(true).queue()
            return
        }
        val action = mapAction(parsed.action) ?: return
        event.deferEdit().queue()
        when (val outcome = blackjackService.applyMultiAction(discordId, table.guildId, table.id, action)) {
            is MultiActionOutcome.Continued -> {
                val live = tableRegistry.get(table.id) ?: return
                event.message.editMessageEmbeds(BlackjackEmbeds.multiHandStateEmbed(live))
                    .setComponents(multiActionRow(table.id))
                    .queue()
            }
            is MultiActionOutcome.HandResolved -> {
                event.message.editMessageEmbeds(BlackjackEmbeds.multiResolvedEmbed(table, outcome.result))
                    .setComponents(emptyList<MessageTopLevelComponent>())
                    .queue()
            }
            MultiActionOutcome.NotYourTurn -> sendError(event, "It isn't your turn yet.")
            MultiActionOutcome.NotSeated -> sendError(event, "You aren't seated at this table.")
            MultiActionOutcome.NoHandInProgress -> sendError(event, "There's no hand in progress on this table.")
            MultiActionOutcome.IllegalAction -> sendError(event, "You can't do that right now.")
            MultiActionOutcome.TableNotFound -> sendError(event, "This blackjack table no longer exists.")
            is MultiActionOutcome.InsufficientCreditsForDouble -> sendError(
                event, "Need ${outcome.needed} credits to double — you only have ${outcome.have}."
            )
            is MultiActionOutcome.InsufficientCreditsForSplit -> sendError(
                event, "Need ${outcome.needed} credits to split — you only have ${outcome.have}."
            )
        }
    }

    private fun handlePeek(event: ButtonInteractionEvent, table: BlackjackTable, discordId: Long) {
        val seat = table.seats.firstOrNull { it.discordId == discordId }
        if (seat == null) {
            event.reply("You aren't seated at this table.").setEphemeral(true).queue()
            return
        }
        event.replyEmbeds(BlackjackEmbeds.peekEmbed(seat.hand)).setEphemeral(true).queue()
    }

    private fun sendError(event: ButtonInteractionEvent, message: String) {
        event.hook.sendMessageEmbeds(BlackjackEmbeds.errorEmbed(message)).setEphemeral(true).queue()
    }

    private fun mapAction(action: BlackjackEmbeds.Action): Blackjack.Action? = when (action) {
        BlackjackEmbeds.Action.HIT -> Blackjack.Action.HIT
        BlackjackEmbeds.Action.STAND -> Blackjack.Action.STAND
        BlackjackEmbeds.Action.DOUBLE -> Blackjack.Action.DOUBLE
        BlackjackEmbeds.Action.SPLIT -> Blackjack.Action.SPLIT
        BlackjackEmbeds.Action.PEEK -> null
    }

    private fun soloActionRow(tableId: Long, allowDouble: Boolean, allowSplit: Boolean): List<ActionRow> {
        val buttons = mutableListOf(
            JdaButton.primary(BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.HIT, tableId), "Hit"),
            JdaButton.success(BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.STAND, tableId), "Stand"),
        )
        if (allowDouble) {
            buttons.add(
                JdaButton.secondary(BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.DOUBLE, tableId), "Double Down")
            )
        }
        if (allowSplit) {
            buttons.add(
                JdaButton.secondary(BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.SPLIT, tableId), "Split")
            )
        }
        return listOf(ActionRow.of(buttons))
    }

    private fun multiActionRow(tableId: Long): List<ActionRow> = listOf(
        ActionRow.of(
            JdaButton.primary(BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.HIT, tableId), "Hit"),
            JdaButton.success(BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.STAND, tableId), "Stand"),
            JdaButton.secondary(BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.DOUBLE, tableId), "Double Down"),
            JdaButton.secondary(BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.SPLIT, tableId), "Split"),
            JdaButton.secondary(BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.PEEK, tableId), "Peek"),
        )
    )
}
