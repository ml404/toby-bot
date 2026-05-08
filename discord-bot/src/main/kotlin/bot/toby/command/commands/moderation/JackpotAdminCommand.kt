package bot.toby.command.commands.moderation

import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import database.service.CasinoAdminService
import database.service.JackpotService
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Owner / superuser admin command for jackpot remediation. Mirrors the
 * `/moderation` casino tab in the web UI: drain the per-guild jackpot
 * pool to zero, or claw an exploit refund back into it from a player.
 *
 * Permission gate matches `SetConfigCommand`: guild owner only, with
 * superuser bypass via the existing `requestingUserDto.superUser` flag.
 */
@Component
class JackpotAdminCommand @Autowired constructor(
    private val casinoAdminService: CasinoAdminService,
    private val jackpotService: JackpotService,
) : ModerationCommand {

    override val name: String = "jackpotadmin"
    override val description: String = "Owner-only jackpot remediation (reset pool, refund from a user)."

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData(SUB_RESET, "Drain the jackpot pool to zero."),
        SubcommandData(SUB_REFUND, "Debit a user and deposit the amount into the jackpot pool.")
            .addOptions(
                OptionData(OptionType.USER, OPT_USER, "User to debit", true),
                OptionData(OptionType.INTEGER, OPT_AMOUNT, "Credits to move into the jackpot pool", true)
                    .setMinValue(1L),
            ),
        SubcommandData(SUB_POOL, "Show the current jackpot pool size."),
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply(true).queue()
        val guildId = event.guild?.idLong ?: run {
            event.hook.sendMessage("Guild only.").setEphemeral(true)
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        if (!isAuthorised(ctx, requestingUserDto)) {
            event.hook.sendMessage("Server owner or superuser only.")
                .setEphemeral(true)
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        when (event.subcommandName) {
            SUB_RESET -> handleReset(event, guildId, deleteDelay)
            SUB_REFUND -> handleRefund(event, guildId, deleteDelay)
            SUB_POOL -> handlePool(event, guildId, deleteDelay)
            else -> event.hook.sendMessage("Pick a subcommand: $SUB_RESET / $SUB_REFUND / $SUB_POOL.")
                .setEphemeral(true)
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
        }
    }

    private fun isAuthorised(ctx: CommandContext, requestingUserDto: UserDto): Boolean =
        ctx.member?.isOwner == true || requestingUserDto.superUser == true

    private fun handleReset(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int) {
        val drained = casinoAdminService.resetJackpot(guildId)
        val message = if (drained == 0L) "Jackpot pool was already empty."
            else "Reset jackpot pool. Drained **$drained** credits."
        event.hook.sendMessage(message).setEphemeral(true)
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun handleRefund(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int) {
        val target = event.getOption(OPT_USER)?.asUser ?: run {
            replyError(event, "User option missing.", deleteDelay); return
        }
        val amount = event.getOption(OPT_AMOUNT)?.asLong ?: run {
            replyError(event, "Amount option missing.", deleteDelay); return
        }
        when (val result = casinoAdminService.refundToJackpot(target.idLong, guildId, amount)) {
            is CasinoAdminService.RefundOutcome.Ok -> event.hook.sendMessage(
                "Refunded **${result.drained}** credits from ${target.asMention} into the jackpot. " +
                    "New pool: **${result.newPool}**. New balance: **${result.newSourceBalance}**."
            ).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
            is CasinoAdminService.RefundOutcome.Insufficient ->
                replyError(
                    event,
                    "${target.asMention} has only **${result.have}** credits, can't refund **${result.needed}**.",
                    deleteDelay,
                )
            is CasinoAdminService.RefundOutcome.InvalidAmount ->
                replyError(event, "Amount must be positive.", deleteDelay)
        }
    }

    private fun handlePool(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int) {
        val pool = jackpotService.getPool(guildId)
        event.hook.sendMessage("Current jackpot pool: **$pool** credits.")
            .setEphemeral(true)
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun replyError(event: SlashCommandInteractionEvent, message: String, deleteDelay: Int) {
        event.hook.sendMessage(message).setEphemeral(true)
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    companion object {
        const val SUB_RESET = "reset"
        const val SUB_REFUND = "refund"
        const val SUB_POOL = "pool"
        const val OPT_USER = "user"
        const val OPT_AMOUNT = "amount"
    }
}
