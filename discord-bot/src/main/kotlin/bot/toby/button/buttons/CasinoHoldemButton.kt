package bot.toby.button.buttons

import bot.toby.command.commands.game.CasinoHoldemEmbeds
import core.button.Button
import core.button.ButtonContext
import database.dto.UserDto
import common.poker.CasinoHoldem
import database.poker.CasinoHoldemTableRegistry
import database.service.CasinoHoldemService
import database.service.CasinoHoldemService.ActionOutcome
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Resolves a click on a `/casinoholdem` action button. The button id
 * encodes the action and table id (`casinoholdem:CALL:<tableId>`); the
 * actual hand state lives in [CasinoHoldemTableRegistry], which the
 * service mutates under a per-table monitor.
 */
@Component
class CasinoHoldemButton @Autowired constructor(
    private val service: CasinoHoldemService,
    private val tableRegistry: CasinoHoldemTableRegistry,
) : Button {

    override val name: String get() = CasinoHoldemEmbeds.BUTTON_NAME
    override val description: String get() = "Call/Fold under a /casinoholdem hand embed."

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        val parsed = CasinoHoldemEmbeds.parseButtonId(event.componentId) ?: run {
            event.reply("Couldn't parse this Casino Hold'em button.").setEphemeral(true).queue()
            return
        }
        val table = tableRegistry.get(parsed.tableId)
        if (table == null || table.guildId != ctx.guild.idLong) {
            event.reply("This Casino Hold'em hand no longer exists.").setEphemeral(true).queue()
            return
        }
        if (table.playerDiscordId != requestingUserDto.discordId) {
            event.reply("This isn't your hand — run `/casinoholdem` to start your own.")
                .setEphemeral(true).queue()
            return
        }

        val action = when (parsed.action) {
            CasinoHoldemEmbeds.Action.CALL -> CasinoHoldem.Action.CALL
            CasinoHoldemEmbeds.Action.FOLD -> CasinoHoldem.Action.FOLD
        }

        event.deferEdit().queue()
        when (val outcome = service.applyAction(
            requestingUserDto.discordId, table.guildId, table.id, action
        )) {
            is ActionOutcome.Resolved -> {
                event.message.editMessageEmbeds(
                    CasinoHoldemEmbeds.resolvedEmbed(
                        table, outcome.result, outcome.newBalance,
                        outcome.jackpotPayout, outcome.lossTribute
                    )
                ).setComponents(emptyList<MessageTopLevelComponent>()).queue()
                service.closeSoloTable(table.id)
            }
            is ActionOutcome.InsufficientCreditsForCall -> sendError(
                event, "Need ${outcome.needed} credits to call — you only have ${outcome.have}."
            )
            ActionOutcome.HandNotFound -> sendError(event, "This hand no longer exists.")
            ActionOutcome.NotYourHand -> sendError(event, "This isn't your hand.")
            ActionOutcome.IllegalAction -> sendError(event, "You can't do that right now.")
        }
    }

    private fun sendError(event: ButtonInteractionEvent, message: String) {
        event.hook.sendMessageEmbeds(CasinoHoldemEmbeds.errorEmbed(message)).setEphemeral(true).queue()
    }
}
