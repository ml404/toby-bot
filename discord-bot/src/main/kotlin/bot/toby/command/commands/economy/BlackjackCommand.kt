package bot.toby.command.commands.economy

import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.blackjack.Blackjack
import database.blackjack.BlackjackTable
import database.blackjack.BlackjackTableRegistry
import database.dto.UserDto
import database.service.BlackjackService
import database.service.BlackjackService.MultiCreateOutcome
import database.service.BlackjackService.MultiJoinOutcome
import database.service.BlackjackService.MultiLeaveOutcome
import database.service.BlackjackService.MultiStartOutcome
import database.service.BlackjackService.SoloDealOutcome
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/blackjack solo|create|join|start|leave|tables|peek` — classic
 * blackjack against the casino dealer (solo) or against a shared dealer
 * with other players (multi). Solo settlement runs through
 * [database.service.WagerHelper] like the rest of the casino games;
 * multi settlement splits a pot among non-bust players that beat the
 * dealer, with a 5 % rake routed to the per-guild jackpot pool.
 */
@Component
class BlackjackCommand @Autowired constructor(
    private val blackjackService: BlackjackService,
    private val tableRegistry: BlackjackTableRegistry,
) : EconomyCommand {

    override val name: String = "blackjack"
    override val description: String =
        "Play blackjack — solo against the dealer or multiplayer with friends."

    companion object {
        private const val SUB_SOLO = "solo"
        private const val SUB_CREATE = "create"
        private const val SUB_JOIN = "join"
        private const val SUB_START = "start"
        private const val SUB_LEAVE = "leave"
        private const val SUB_TABLES = "tables"
        private const val SUB_PEEK = "peek"

        private const val OPT_STAKE = "stake"
        private const val OPT_ANTE = "ante"
        private const val OPT_TABLE = "table"
    }

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData(SUB_SOLO, "Play one hand of blackjack against the dealer.")
            .addOptions(
                OptionData(OptionType.INTEGER, OPT_STAKE, "Credits to wager (${Blackjack.MIN_STAKE}-${Blackjack.MAX_STAKE})", true)
                    .setMinValue(Blackjack.MIN_STAKE)
                    .setMaxValue(Blackjack.MAX_STAKE)
            ),
        SubcommandData(SUB_CREATE, "Create a multiplayer blackjack table and seat yourself.")
            .addOptions(
                OptionData(OptionType.INTEGER, OPT_ANTE, "Per-hand ante (${Blackjack.MULTI_MIN_ANTE}-${Blackjack.MULTI_MAX_ANTE})", true)
                    .setMinValue(Blackjack.MULTI_MIN_ANTE)
                    .setMaxValue(Blackjack.MULTI_MAX_ANTE)
            ),
        SubcommandData(SUB_JOIN, "Join an existing blackjack table at its ante.")
            .addOptions(
                OptionData(OptionType.INTEGER, OPT_TABLE, "Table id", true).setMinValue(1)
            ),
        SubcommandData(SUB_START, "Deal the next hand on a table you host.")
            .addOptions(
                OptionData(OptionType.INTEGER, OPT_TABLE, "Table id", true).setMinValue(1)
            ),
        SubcommandData(SUB_LEAVE, "Leave a multiplayer table (between hands only) and refund your ante.")
            .addOptions(
                OptionData(OptionType.INTEGER, OPT_TABLE, "Table id", true).setMinValue(1)
            ),
        SubcommandData(SUB_TABLES, "List active blackjack tables in this server."),
        SubcommandData(SUB_PEEK, "Show your hand mid-round (only visible to you).")
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
            SUB_SOLO -> handleSolo(event, requestingUserDto, guild.idLong, deleteDelay)
            SUB_CREATE -> handleCreate(event, requestingUserDto, guild.idLong, deleteDelay)
            SUB_JOIN -> handleJoin(event, requestingUserDto, guild.idLong, deleteDelay)
            SUB_START -> handleStart(event, requestingUserDto, guild.idLong, deleteDelay)
            SUB_LEAVE -> handleLeave(event, requestingUserDto, guild.idLong, deleteDelay)
            SUB_TABLES -> handleTables(event, guild.idLong, deleteDelay)
            SUB_PEEK -> handlePeek(event, requestingUserDto, guild.idLong)
            else -> replyError(event, "Unknown subcommand.", deleteDelay)
        }
    }

    private fun handleSolo(
        event: SlashCommandInteractionEvent,
        userDto: UserDto,
        guildId: Long,
        deleteDelay: Int
    ) {
        val stake = event.getOption(OPT_STAKE)?.asLong ?: run {
            replyError(event, "You must specify a stake.", deleteDelay); return
        }
        when (val outcome = blackjackService.dealSolo(userDto.discordId, guildId, stake)) {
            is SoloDealOutcome.Dealt -> {
                val table = tableRegistry.get(outcome.tableId)
                    ?: return replyError(event, "Hand vanished.", deleteDelay)
                event.hook.sendMessageEmbeds(BlackjackEmbeds.soloDealEmbed(table))
                    .addComponents(soloActionRow(outcome.tableId, allowDouble = true))
                    .queue()
            }
            is SoloDealOutcome.Resolved -> {
                val table = tableRegistry.get(outcome.tableId)
                    ?: return replyError(event, "Hand vanished.", deleteDelay)
                event.hook.sendMessageEmbeds(
                    BlackjackEmbeds.soloResolvedEmbed(
                        table, outcome.result, outcome.newBalance, outcome.jackpotPayout, outcome.lossTribute
                    )
                ).queue(invokeDeleteOnMessageResponse(deleteDelay))
                blackjackService.closeSoloTable(outcome.tableId)
            }
            is SoloDealOutcome.InvalidStake -> replyFailure(
                event, WagerCommandFailure.InvalidStake(outcome.min, outcome.max), deleteDelay
            )
            is SoloDealOutcome.InsufficientCredits -> replyFailure(
                event, WagerCommandFailure.InsufficientCredits(outcome.stake, outcome.have), deleteDelay
            )
            SoloDealOutcome.UnknownUser -> replyFailure(
                event, WagerCommandFailure.UnknownUser, deleteDelay
            )
        }
    }

    private fun handleCreate(
        event: SlashCommandInteractionEvent,
        userDto: UserDto,
        guildId: Long,
        deleteDelay: Int
    ) {
        val ante = event.getOption(OPT_ANTE)?.asLong ?: run {
            replyError(event, "Ante is required.", deleteDelay); return
        }
        when (val outcome = blackjackService.createMultiTable(userDto.discordId, guildId, ante)) {
            is MultiCreateOutcome.Ok -> {
                val table = tableRegistry.get(outcome.tableId)
                    ?: return replyError(event, "Table vanished.", deleteDelay)
                event.hook.sendMessageEmbeds(BlackjackEmbeds.lobbyEmbed(table))
                    .queue(invokeDeleteOnMessageResponse(deleteDelay))
            }
            is MultiCreateOutcome.InvalidAnte -> replyFailure(
                event, WagerCommandFailure.InvalidStake(outcome.min, outcome.max), deleteDelay
            )
            is MultiCreateOutcome.InsufficientCredits -> replyFailure(
                event, WagerCommandFailure.InsufficientCredits(outcome.stake, outcome.have), deleteDelay
            )
            MultiCreateOutcome.UnknownUser -> replyFailure(
                event, WagerCommandFailure.UnknownUser, deleteDelay
            )
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
        when (val outcome = blackjackService.joinMultiTable(userDto.discordId, guildId, tableId)) {
            is MultiJoinOutcome.Ok -> {
                val table = tableRegistry.get(tableId)
                    ?: return replyError(event, "Table vanished.", deleteDelay)
                event.hook.sendMessageEmbeds(
                    BlackjackEmbeds.infoEmbed("<@${userDto.discordId}> joined table #$tableId."),
                    BlackjackEmbeds.lobbyEmbed(table)
                ).queue(invokeDeleteOnMessageResponse(deleteDelay))
            }
            MultiJoinOutcome.AlreadySeated ->
                replyError(event, "You're already at this table.", deleteDelay)
            MultiJoinOutcome.TableFull ->
                replyError(event, "Table #$tableId is full.", deleteDelay)
            MultiJoinOutcome.TableNotFound ->
                replyError(event, "No such table in this server.", deleteDelay)
            MultiJoinOutcome.HandInProgress ->
                replyError(event, "Wait for the current hand to end before joining.", deleteDelay)
            is MultiJoinOutcome.InsufficientCredits -> replyFailure(
                event, WagerCommandFailure.InsufficientCredits(outcome.stake, outcome.have), deleteDelay
            )
            MultiJoinOutcome.UnknownUser -> replyFailure(
                event, WagerCommandFailure.UnknownUser, deleteDelay
            )
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
        when (val outcome = blackjackService.startMultiHand(userDto.discordId, guildId, tableId)) {
            is MultiStartOutcome.Ok -> {
                val table = tableRegistry.get(tableId)
                    ?: return replyError(event, "Table vanished.", deleteDelay)
                event.hook.sendMessageEmbeds(BlackjackEmbeds.multiHandStateEmbed(table))
                    .addComponents(multiActionRow(tableId))
                    .queue()
            }
            MultiStartOutcome.NotEnoughPlayers ->
                replyError(event, "Need at least ${Blackjack.MULTI_MIN_SEATS} seated players to deal a hand.", deleteDelay)
            MultiStartOutcome.HandAlreadyInProgress ->
                replyError(event, "A hand is already in progress on this table.", deleteDelay)
            MultiStartOutcome.NotHost ->
                replyError(event, "Only the table host can deal hands.", deleteDelay)
            MultiStartOutcome.TableNotFound ->
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
        when (val outcome = blackjackService.leaveMultiTable(userDto.discordId, guildId, tableId)) {
            is MultiLeaveOutcome.Ok -> event.hook.sendMessageEmbeds(
                BlackjackEmbeds.infoEmbed(
                    "<@${userDto.discordId}> left table #$tableId — refunded **${outcome.refund}** credits. " +
                        "Balance: ${outcome.newBalance}."
                )
            ).queue(invokeDeleteOnMessageResponse(deleteDelay))
            MultiLeaveOutcome.HandInProgress ->
                replyError(event, "Can't leave during a hand — finish playing it out first.", deleteDelay)
            MultiLeaveOutcome.NotSeated ->
                replyError(event, "You're not seated at this table.", deleteDelay)
            MultiLeaveOutcome.TableNotFound ->
                replyError(event, "No such table in this server.", deleteDelay)
        }
    }

    private fun handleTables(
        event: SlashCommandInteractionEvent,
        guildId: Long,
        deleteDelay: Int
    ) {
        val tables = tableRegistry.listForGuild(guildId)
            .filter { it.mode == BlackjackTable.Mode.MULTI }
        if (tables.isEmpty()) {
            event.hook.sendMessageEmbeds(
                BlackjackEmbeds.infoEmbed(
                    "No active blackjack tables in this server. " +
                        "`/blackjack create ante:<amount>` to start one."
                )
            ).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        val description = tables.joinToString("\n") {
            "• Table #${it.id} — ${it.seats.size}/${it.maxSeats} seats, " +
                "ante ${it.ante}, host <@${it.hostDiscordId}>, ${it.phase}"
        }
        event.hook.sendMessageEmbeds(BlackjackEmbeds.infoEmbed(description))
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun handlePeek(
        event: SlashCommandInteractionEvent,
        userDto: UserDto,
        guildId: Long,
    ) {
        val tableId = event.getOption(OPT_TABLE)?.asLong ?: run {
            event.hook.sendMessageEmbeds(BlackjackEmbeds.errorEmbed("Table id is required."))
                .setEphemeral(true).queue(); return
        }
        val table = tableRegistry.get(tableId)
        if (table == null || table.guildId != guildId) {
            event.hook.sendMessageEmbeds(BlackjackEmbeds.errorEmbed("No such table in this server."))
                .setEphemeral(true).queue(); return
        }
        val seat = table.seats.firstOrNull { it.discordId == userDto.discordId }
        if (seat == null) {
            event.hook.sendMessageEmbeds(BlackjackEmbeds.errorEmbed("You're not seated at this table."))
                .setEphemeral(true).queue(); return
        }
        event.hook.sendMessageEmbeds(BlackjackEmbeds.peekEmbed(seat.hand))
            .setEphemeral(true).queue()
    }

    private fun soloActionRow(tableId: Long, allowDouble: Boolean): ActionRow {
        val buttons = mutableListOf(
            Button.primary(BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.HIT, tableId), "Hit"),
            Button.success(BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.STAND, tableId), "Stand"),
        )
        if (allowDouble) {
            buttons.add(
                Button.secondary(BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.DOUBLE, tableId), "Double Down")
            )
        }
        return ActionRow.of(buttons)
    }

    private fun multiActionRow(tableId: Long): ActionRow = ActionRow.of(
        Button.primary(BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.HIT, tableId), "Hit"),
        Button.success(BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.STAND, tableId), "Stand"),
        Button.secondary(BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.DOUBLE, tableId), "Double Down"),
        Button.secondary(BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.PEEK, tableId), "Peek"),
    )

    private fun replyError(
        event: SlashCommandInteractionEvent,
        message: String,
        deleteDelay: Int
    ) {
        event.hook.sendMessageEmbeds(BlackjackEmbeds.errorEmbed(message))
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun replyFailure(
        event: SlashCommandInteractionEvent,
        failure: WagerCommandFailure,
        deleteDelay: Int
    ) {
        event.hook.sendMessageEmbeds(WagerCommandEmbeds.failureEmbed("🂡 Blackjack", failure))
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

}
