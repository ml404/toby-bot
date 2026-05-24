package bot.toby.command.commands.economy

import web.service.TitleRoleResult
import web.service.TitleRoleService
import core.command.Command.Companion.replyEphemeralAndDelete
import core.command.Command.Companion.replyEphemeralEmbedAndDelete
import core.command.CommandContext
import database.dto.user.UserDto
import database.service.guild.TitlePurchasePolicy
import database.service.guild.TitleService
import database.service.user.UserService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class TitleCommand @Autowired constructor(
    private val titleService: TitleService,
    private val userService: UserService,
    private val titleRoleService: TitleRoleService
) : EconomyCommand {

    override val name: String = "title"
    override val description: String = "Browse, buy, and equip vanity titles with your social credit."

    companion object {
        private const val OPT_TITLE = "title"

        /** Renders the title-shop body text. Pure function for unit testing. */
        internal fun buildShopBody(titles: List<database.dto.guild.TitleDto>): String =
            if (titles.isEmpty()) {
                "No titles are available right now."
            } else {
                titles.joinToString("\n") { t ->
                    val desc = if (!t.description.isNullOrBlank()) " — ${t.description}" else ""
                    val gate = if (t.requiredLevel > 0) " · 🔒 Lvl ${t.requiredLevel}" else ""
                    "**${t.label}** · ${t.cost} credits$gate$desc"
                }
            }
    }

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData("shop", "List titles available for purchase."),
        SubcommandData("buy", "Buy a title with your social credit.")
            .addOptions(
                OptionData(OptionType.STRING, OPT_TITLE, "Exact title label (e.g. '⭐ Comrade')", true)
            ),
        SubcommandData("equip", "Equip a title you own so it appears on the leaderboard and grants the matching Discord role.")
            .addOptions(
                OptionData(OptionType.STRING, OPT_TITLE, "Exact title label to equip", true)
            ),
        SubcommandData("unequip", "Remove your currently equipped title."),
        SubcommandData("list", "List the titles you own and your currently equipped title.")
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply(true).queue()

        val guild = event.guild ?: run {
            event.hook.replyEphemeralAndDelete("This command can only be used in a server.", deleteDelay)
            return
        }
        val member = event.member ?: run {
            event.hook.replyEphemeralAndDelete("Could not resolve your member info.", deleteDelay)
            return
        }

        when (event.subcommandName) {
            "shop" -> handleShop(event, deleteDelay)
            "buy" -> handleBuy(event, requestingUserDto, deleteDelay)
            "equip" -> handleEquip(event, guild, member, requestingUserDto, deleteDelay)
            "unequip" -> handleUnequip(event, guild, member, requestingUserDto, deleteDelay)
            "list" -> handleList(event, requestingUserDto, deleteDelay)
            else -> event.hook.replyEphemeralAndDelete("Unknown subcommand.", deleteDelay)
        }
    }

    private fun handleShop(event: SlashCommandInteractionEvent, deleteDelay: Int) {
        val embed = EmbedBuilder()
            .setTitle("Title Shop")
            .setDescription(buildShopBody(titleService.listAll()))
            .setFooter("Buy with /title buy <exact label>")
            .build()
        event.hook.replyEphemeralEmbedAndDelete(embed, deleteDelay)
    }

    private fun handleBuy(event: SlashCommandInteractionEvent, userDto: UserDto, deleteDelay: Int) {
        val label = event.getOption(OPT_TITLE)?.asString?.trim().orEmpty()
        if (label.isEmpty()) {
            reply(event, "You must specify a title label.", deleteDelay); return
        }
        val title = titleService.getByLabel(label)
        if (title == null) {
            reply(event, "No title matches '$label'. Use /title shop to see available titles.", deleteDelay); return
        }
        val titleId = title.id ?: run {
            reply(event, "Title has no id. Try again.", deleteDelay); return
        }
        if (titleService.owns(userDto.discordId, titleId)) {
            reply(event, "You already own **${title.label}**. Use /title equip to wear it.", deleteDelay); return
        }
        when (val gate = TitlePurchasePolicy.check(title, userDto.xp)) {
            is TitlePurchasePolicy.Result.LevelLocked -> {
                reply(
                    event,
                    "Requires Level ${gate.required} to buy **${title.label}** — you are Level ${gate.actor}. " +
                        "Keep chatting / hanging in voice — you'll unlock it free when you ding.",
                    deleteDelay
                )
                return
            }
            TitlePurchasePolicy.Result.Ok -> Unit
        }
        val balance = userDto.socialCredit ?: 0L
        if (balance < title.cost) {
            reply(event, "You need ${title.cost} credits but only have $balance. Earn more by chatting and spending time in voice.", deleteDelay); return
        }

        userDto.socialCredit = balance - title.cost
        userService.updateUser(userDto)
        titleService.recordPurchase(userDto.discordId, titleId)
        reply(event, "Bought **${title.label}** for ${title.cost} credits. You now have ${userDto.socialCredit} credits. Equip it with /title equip.", deleteDelay)
    }

    private fun handleEquip(
        event: SlashCommandInteractionEvent,
        guild: Guild,
        member: Member,
        userDto: UserDto,
        deleteDelay: Int
    ) {
        val label = event.getOption(OPT_TITLE)?.asString?.trim().orEmpty()
        if (label.isEmpty()) {
            reply(event, "You must specify a title label.", deleteDelay); return
        }
        val title = titleService.getByLabel(label)
        if (title == null) {
            reply(event, "No title matches '$label'.", deleteDelay); return
        }
        val titleId = title.id ?: run {
            reply(event, "Title has no id. Try again.", deleteDelay); return
        }
        if (!titleService.owns(userDto.discordId, titleId)) {
            reply(event, "You don't own **${title.label}**. Buy it with /title buy first.", deleteDelay); return
        }

        val ownedIds = titleService.listOwned(userDto.discordId).map { it.titleId }.toSet()
        when (val result = titleRoleService.equip(guild, member, title, ownedIds)) {
            is TitleRoleResult.Ok -> {
                userDto.activeTitleId = titleId
                userService.updateUser(userDto)
                reply(event, "Equipped **${title.label}**. Your Discord role has been updated.", deleteDelay)
            }
            is TitleRoleResult.Error -> {
                reply(event, "Could not equip **${title.label}** — ${result.message}", deleteDelay)
            }
        }
    }

    private fun handleUnequip(
        event: SlashCommandInteractionEvent,
        guild: Guild,
        member: Member,
        userDto: UserDto,
        deleteDelay: Int
    ) {
        if (userDto.activeTitleId == null) {
            reply(event, "You don't have a title equipped.", deleteDelay); return
        }
        val ownedIds = titleService.listOwned(userDto.discordId).map { it.titleId }.toSet()
        when (val result = titleRoleService.unequip(guild, member, ownedIds)) {
            is TitleRoleResult.Ok -> {
                userDto.activeTitleId = null
                userService.updateUser(userDto)
                reply(event, "Title unequipped.", deleteDelay)
            }
            is TitleRoleResult.Error -> {
                reply(event, "Could not unequip — ${result.message}", deleteDelay)
            }
        }
    }

    private fun handleList(event: SlashCommandInteractionEvent, userDto: UserDto, deleteDelay: Int) {
        val owned = titleService.listOwned(userDto.discordId)
        if (owned.isEmpty()) {
            reply(event, "You don't own any titles yet. Use /title shop to see what's on offer.", deleteDelay); return
        }
        val titles = owned.mapNotNull { titleService.getById(it.titleId) }
        val equippedId = userDto.activeTitleId
        val lines = titles.joinToString("\n") { t ->
            val marker = if (t.id == equippedId) " ← equipped" else ""
            "• ${t.label}$marker"
        }
        reply(event, "**Your titles**\n$lines", deleteDelay)
    }

    private fun reply(event: SlashCommandInteractionEvent, message: String, deleteDelay: Int) {
        event.hook.replyEphemeralAndDelete(message, deleteDelay)
    }
}
