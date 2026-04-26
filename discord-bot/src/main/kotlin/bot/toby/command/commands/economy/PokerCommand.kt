package bot.toby.command.commands.economy

import bot.toby.helpers.UserDtoHelper
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import database.poker.PokerTable
import database.poker.PokerTableRegistry
import database.service.PokerService
import database.service.PokerService.BuyInOutcome
import database.service.PokerService.CashOutOutcome
import database.service.PokerService.CreateOutcome
import database.service.PokerService.StartHandOutcome
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/poker create|join|start|leave|tables` — multiplayer fixed-limit
 * Texas Hold'em over the social-credit economy.
 *
 * The state machine lives in [database.poker.PokerEngine] and is driven
 * by [PokerService], which keeps tables in [PokerTableRegistry] (shared
 * with the web layer) and routes 5 % of every settled pot into the
 * existing per-guild jackpot pool. Per-turn betting is handled by
 * [bot.toby.button.buttons.PokerActionButton] under the hand-state
 * embed posted by `/poker start` (and re-posted on each street).
 */
@Component
class PokerCommand @Autowired constructor(
    private val pokerService: PokerService,
    private val tableRegistry: PokerTableRegistry,
    private val userDtoHelper: UserDtoHelper,
) : EconomyCommand {

    override val name: String = "poker"
    override val description: String =
        "Multiplayer Texas Hold'em (fixed-limit). Buy in, deal hands, take credits from your friends."

    companion object {
        private const val OPT_BUYIN = "chips"
        private const val OPT_TABLE = "table"

        private const val SUB_CREATE = "create"
        private const val SUB_JOIN = "join"
        private const val SUB_START = "start"
        private const val SUB_LEAVE = "leave"
        private const val SUB_TABLES = "tables"
        private const val SUB_PEEK = "peek"
    }

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData(SUB_CREATE, "Create a new poker table and seat yourself with the given buy-in.")
            .addOptions(
                OptionData(OptionType.INTEGER, OPT_BUYIN, "Chips to buy in for", true)
                    .setMinValue(PokerService.MIN_BUY_IN)
                    .setMaxValue(PokerService.MAX_BUY_IN)
            ),
        SubcommandData(SUB_JOIN, "Join an existing table with a buy-in.")
            .addOptions(
                OptionData(OptionType.INTEGER, OPT_TABLE, "Table id", true).setMinValue(1),
                OptionData(OptionType.INTEGER, OPT_BUYIN, "Chips to buy in for", true)
                    .setMinValue(PokerService.MIN_BUY_IN)
                    .setMaxValue(PokerService.MAX_BUY_IN)
            ),
        SubcommandData(SUB_START, "Deal the next hand on a table you host.")
            .addOptions(
                OptionData(OptionType.INTEGER, OPT_TABLE, "Table id", true).setMinValue(1)
            ),
        SubcommandData(SUB_LEAVE, "Cash out your remaining chips and leave a table (between hands only).")
            .addOptions(
                OptionData(OptionType.INTEGER, OPT_TABLE, "Table id", true).setMinValue(1)
            ),
        SubcommandData(SUB_TABLES, "List active poker tables in this server."),
        SubcommandData(SUB_PEEK, "Show your hole cards (only visible to you).")
            .addOptions(
                OptionData(OptionType.INTEGER, OPT_TABLE, "Table id", true).setMinValue(1)
            ),
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = event.guild ?: run {
            replyError(event, "This command can only be used in a server.", deleteDelay); return
        }

        when (event.subcommandName) {
            SUB_CREATE -> handleCreate(event, requestingUserDto, guild.idLong, deleteDelay)
            SUB_JOIN -> handleJoin(event, requestingUserDto, guild.idLong, deleteDelay)
            SUB_START -> handleStart(event, requestingUserDto, guild.idLong, deleteDelay)
            SUB_LEAVE -> handleLeave(event, requestingUserDto, guild.idLong, deleteDelay)
            SUB_TABLES -> handleTables(event, guild.idLong, deleteDelay)
            SUB_PEEK -> handlePeek(event, requestingUserDto, guild.idLong)
            else -> replyError(event, "Unknown subcommand.", deleteDelay)
        }
    }

    private fun handleCreate(
        event: SlashCommandInteractionEvent,
        userDto: UserDto,
        guildId: Long,
        deleteDelay: Int
    ) {
        val buyIn = event.getOption(OPT_BUYIN)?.asLong ?: run {
            replyError(event, "Buy-in is required.", deleteDelay); return
        }
        userDtoHelper.calculateUserDto(userDto.discordId, guildId)
        when (val outcome = pokerService.createTable(userDto.discordId, guildId, buyIn)) {
            is CreateOutcome.Ok -> {
                val table = tableRegistry.get(outcome.tableId)
                    ?: return replyError(event, "Table vanished.", deleteDelay)
                event.hook.sendMessageEmbeds(PokerEmbeds.lobbyEmbed(table))
                    .queue(invokeDeleteOnMessageResponse(deleteDelay))
            }
            is CreateOutcome.InvalidBuyIn ->
                replyError(event, "Buy-in must be between ${outcome.min} and ${outcome.max} credits.", deleteDelay)
            is CreateOutcome.InsufficientCredits ->
                replyError(event, "You need ${outcome.needed} credits but only have ${outcome.have}.", deleteDelay)
            CreateOutcome.UnknownUser ->
                replyError(event, "No user record yet. Try another TobyBot command first.", deleteDelay)
        }
    }

    private fun handleJoin(
        event: SlashCommandInteractionEvent,
        userDto: UserDto,
        guildId: Long,
        deleteDelay: Int
    ) {
        val tableId = event.getOption(OPT_TABLE)?.asLong ?: run {
            replyError(event, "Table id is required.", deleteDelay); return
        }
        val buyIn = event.getOption(OPT_BUYIN)?.asLong ?: run {
            replyError(event, "Buy-in is required.", deleteDelay); return
        }
        when (val outcome = pokerService.buyIn(userDto.discordId, guildId, tableId, buyIn)) {
            is BuyInOutcome.Ok -> {
                val table = tableRegistry.get(tableId)
                    ?: return replyError(event, "Table vanished.", deleteDelay)
                event.hook.sendMessageEmbeds(
                    PokerEmbeds.infoEmbed("<@${userDto.discordId}> joined table #$tableId with $buyIn chips."),
                    PokerEmbeds.lobbyEmbed(table)
                ).queue(invokeDeleteOnMessageResponse(deleteDelay))
            }
            is BuyInOutcome.AlreadySeated ->
                replyError(event, "You're already at this table.", deleteDelay)
            is BuyInOutcome.TableFull ->
                replyError(event, "Table #$tableId is full (${PokerService.MAX_SEATS} seats).", deleteDelay)
            is BuyInOutcome.TableNotFound ->
                replyError(event, "No such table in this server.", deleteDelay)
            is BuyInOutcome.InvalidBuyIn ->
                replyError(event, "Buy-in must be between ${outcome.min} and ${outcome.max} credits.", deleteDelay)
            is BuyInOutcome.InsufficientCredits ->
                replyError(event, "You need ${outcome.needed} credits but only have ${outcome.have}.", deleteDelay)
            BuyInOutcome.UnknownUser ->
                replyError(event, "No user record yet. Try another TobyBot command first.", deleteDelay)
        }
    }

    private fun handleStart(
        event: SlashCommandInteractionEvent,
        userDto: UserDto,
        guildId: Long,
        deleteDelay: Int
    ) {
        val tableId = event.getOption(OPT_TABLE)?.asLong ?: run {
            replyError(event, "Table id is required.", deleteDelay); return
        }
        val outcome = pokerService.startHand(userDto.discordId, guildId, tableId)
        when (outcome) {
            is StartHandOutcome.Ok -> {
                val table = tableRegistry.get(tableId)
                    ?: return replyError(event, "Table vanished.", deleteDelay)
                postHandState(event, table, deleteDelay)
            }
            StartHandOutcome.NotEnoughPlayers ->
                replyError(event, "Need at least 2 seated players with chips to deal a hand.", deleteDelay)
            StartHandOutcome.HandAlreadyInProgress ->
                replyError(event, "A hand is already in progress on this table.", deleteDelay)
            StartHandOutcome.NotHost ->
                replyError(event, "Only the table host can deal hands.", deleteDelay)
            StartHandOutcome.TableNotFound ->
                replyError(event, "No such table in this server.", deleteDelay)
        }
    }

    private fun handleLeave(
        event: SlashCommandInteractionEvent,
        userDto: UserDto,
        guildId: Long,
        deleteDelay: Int
    ) {
        val tableId = event.getOption(OPT_TABLE)?.asLong ?: run {
            replyError(event, "Table id is required.", deleteDelay); return
        }
        when (val outcome = pokerService.cashOut(userDto.discordId, guildId, tableId)) {
            is CashOutOutcome.Ok -> event.hook.sendMessageEmbeds(
                PokerEmbeds.infoEmbed(
                    "<@${userDto.discordId}> cashed out **${outcome.chipsReturned}** chips. " +
                        "New balance: ${outcome.newBalance} credits."
                )
            ).queue(invokeDeleteOnMessageResponse(deleteDelay))
            CashOutOutcome.HandInProgress ->
                replyError(event, "Wait for the current hand to end before leaving (fold first if you don't want to play it out).", deleteDelay)
            CashOutOutcome.NotSeated ->
                replyError(event, "You're not seated at this table.", deleteDelay)
            CashOutOutcome.TableNotFound ->
                replyError(event, "No such table in this server.", deleteDelay)
        }
    }

    private fun handleTables(
        event: SlashCommandInteractionEvent,
        guildId: Long,
        deleteDelay: Int
    ) {
        val tables = tableRegistry.listForGuild(guildId)
        if (tables.isEmpty()) {
            event.hook.sendMessageEmbeds(
                PokerEmbeds.infoEmbed("No active poker tables in this server. `/poker create chips:<amount>` to start one.")
            ).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        val description = tables.joinToString("\n") {
            "• Table #${it.id} — ${it.seats.size}/${it.maxSeats} seats, host <@${it.hostDiscordId}>, phase ${it.phase}"
        }
        event.hook.sendMessageEmbeds(PokerEmbeds.infoEmbed(description))
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun handlePeek(
        event: SlashCommandInteractionEvent,
        userDto: UserDto,
        guildId: Long,
    ) {
        val tableId = event.getOption(OPT_TABLE)?.asLong ?: run {
            event.hook.sendMessageEmbeds(PokerEmbeds.errorEmbed("Table id is required."))
                .setEphemeral(true).queue(); return
        }
        val table = tableRegistry.get(tableId)
        if (table == null || table.guildId != guildId) {
            event.hook.sendMessageEmbeds(PokerEmbeds.errorEmbed("No such table in this server."))
                .setEphemeral(true).queue(); return
        }
        val seat = table.seats.firstOrNull { it.discordId == userDto.discordId }
        if (seat == null) {
            event.hook.sendMessageEmbeds(PokerEmbeds.errorEmbed("You're not seated at this table."))
                .setEphemeral(true).queue(); return
        }
        event.hook.sendMessageEmbeds(PokerEmbeds.peekEmbed(seat.holeCards))
            .setEphemeral(true).queue()
    }

    private fun postHandState(
        event: SlashCommandInteractionEvent,
        table: PokerTable,
        deleteDelay: Int
    ) {
        val embed: MessageEmbed = PokerEmbeds.handStateEmbed(table)
        val row = ActionRow.of(
            Button.primary(PokerEmbeds.buttonId(PokerEmbeds.Action.CHECK_CALL, table.id), "Check / Call"),
            Button.success(PokerEmbeds.buttonId(PokerEmbeds.Action.RAISE, table.id), "Raise"),
            Button.danger(PokerEmbeds.buttonId(PokerEmbeds.Action.FOLD, table.id), "Fold"),
            Button.secondary(PokerEmbeds.buttonId(PokerEmbeds.Action.PEEK, table.id), "Peek cards"),
        )
        event.hook.sendMessageEmbeds(embed).addComponents(row).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun replyError(
        event: SlashCommandInteractionEvent,
        message: String,
        deleteDelay: Int
    ) {
        event.hook.sendMessageEmbeds(PokerEmbeds.errorEmbed(message))
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }
}
