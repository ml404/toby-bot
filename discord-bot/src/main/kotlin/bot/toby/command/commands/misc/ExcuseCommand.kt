package bot.toby.command.commands.misc

import core.command.Command.Companion.replyAndDelete
import core.command.Command.Companion.replyEmbedAndDelete
import core.command.CommandContext
import database.dto.ExcuseDto
import database.dto.UserDto
import database.service.ExcuseService
import database.service.PagedExcuses
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.awt.Color

@Component
class ExcuseCommand @Autowired constructor(
    private val excuseService: ExcuseService
) : MiscCommand {

    override val name = "excuse"
    override val description = "Random excuses, submissions, and moderation per server."

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData(RANDOM, "Get a random approved excuse for this server."),
        SubcommandData(SUBMIT, "Submit a new excuse for approval (max 200 characters).")
            .addOptions(
                OptionData(OptionType.STRING, OPT_TEXT, "The excuse text", true)
                    .setMaxLength(MAX_EXCUSE_LENGTH.toLong()),
                OptionData(
                    OptionType.USER, OPT_AUTHOR,
                    "Attribute the excuse to this user instead of yourself", false
                )
            ),
        SubcommandData(LIST, "Browse the server's excuses with pagination.")
            .addOptions(
                OptionData(OptionType.STRING, OPT_SCOPE, "Approved (default) or pending — pending is superuser-only")
                    .addChoice(SCOPE_APPROVED, SCOPE_APPROVED)
                    .addChoice(SCOPE_PENDING, SCOPE_PENDING),
                OptionData(OptionType.INTEGER, OPT_PAGE, "Page number (default 1)")
            ),
        SubcommandData(SEARCH, "Find approved excuses containing the given text.")
            .addOptions(
                OptionData(OptionType.STRING, OPT_QUERY, "Search text (case-insensitive substring)", true),
                OptionData(OptionType.INTEGER, OPT_PAGE, "Page number (default 1)")
            ),
        SubcommandData(APPROVE, "Approve a pending excuse (superuser only).")
            .addOptions(
                OptionData(OptionType.INTEGER, OPT_ID, "Excuse id to approve", true)
            ),
        SubcommandData(DELETE, "Delete an excuse (superusers, or authors deleting their own pending submission).")
            .addOptions(
                OptionData(OptionType.INTEGER, OPT_ID, "Excuse id to delete", true)
            ),
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guildId = event.guild?.idLong ?: run {
            event.hook.replyAndDelete("This command can only be used in a server.", deleteDelay)
            return
        }

        when (event.subcommandName) {
            RANDOM, null -> handleRandom(event, guildId, deleteDelay)
            SUBMIT -> handleSubmit(event, guildId, requestingUserDto, deleteDelay)
            LIST -> handleList(event, guildId, requestingUserDto, deleteDelay)
            SEARCH -> handleSearch(event, guildId, deleteDelay)
            APPROVE -> handleApprove(event, requestingUserDto, deleteDelay)
            DELETE -> handleDelete(event, requestingUserDto, deleteDelay)
            else -> event.hook.replyAndDelete(
                "Unknown subcommand. Try `/excuse random`, `/excuse submit`, `/excuse list`, `/excuse search`.",
                deleteDelay,
            )
        }
    }

    private fun handleRandom(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int) {
        val approved = excuseService.listApprovedGuildExcuses(guildId).filterNotNull()
        if (approved.isEmpty()) {
            event.hook.replyAndDelete("There are no approved excuses, consider submitting some.", deleteDelay)
            return
        }
        val pick = approved.random()
        event.hook.replyAndDelete(
            "Excuse #${pick.id}: '${pick.excuse}' - ${pick.author}.",
            deleteDelay,
        )
    }

    private fun handleSubmit(
        event: SlashCommandInteractionEvent,
        guildId: Long,
        requesterDto: UserDto,
        deleteDelay: Int,
    ) {
        val excuseText = event.getOption(OPT_TEXT)?.asString?.trim()
        if (excuseText.isNullOrBlank()) {
            event.hook.replyAndDelete("Provide some excuse text.", deleteDelay)
            return
        }

        val authorMember = event.getOption(OPT_AUTHOR)?.asMember
        val authorName = authorMember?.effectiveName ?: event.user.name
        val authorDiscordId = authorMember?.idLong ?: event.user.idLong

        val existing = excuseService.listAllGuildExcuses(guildId)
            .filterNotNull()
            .firstOrNull { it.excuse.equals(excuseText, ignoreCase = true) }
        if (existing != null) {
            event.hook.replyAndDelete(EXISTING_EXCUSE_MESSAGE, deleteDelay)
            return
        }

        val dto = ExcuseDto(
            guildId = guildId,
            author = authorName,
            excuse = excuseText,
            authorDiscordId = authorDiscordId,
        )
        val saved = excuseService.createNewExcuse(dto)
        event.hook.replyAndDelete(
            "Submitted excuse '$excuseText' - $authorName with id '${saved?.id}' for approval.",
            deleteDelay,
        )
    }

    private fun handleList(
        event: SlashCommandInteractionEvent,
        guildId: Long,
        requesterDto: UserDto,
        deleteDelay: Int,
    ) {
        val scope = event.getOption(OPT_SCOPE)?.asString ?: SCOPE_APPROVED
        val page = (event.getOption(OPT_PAGE)?.asInt ?: 1).coerceAtLeast(1)

        if (scope == SCOPE_PENDING && !requesterDto.superUser) {
            sendErrorMessage(event, deleteDelay)
            return
        }

        val paged = when (scope) {
            SCOPE_PENDING -> excuseService.listPendingPaged(guildId, page, EXCUSES_PER_PAGE)
            else -> excuseService.listApprovedPaged(guildId, page, EXCUSES_PER_PAGE)
        }
        sendPagedListing(
            event = event,
            paged = paged,
            scope = scope,
            query = null,
            guildId = guildId,
            deleteDelay = deleteDelay,
        )
    }

    private fun handleSearch(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int) {
        val query = event.getOption(OPT_QUERY)?.asString?.trim()
        if (query.isNullOrBlank()) {
            event.hook.replyAndDelete("Provide a search query.", deleteDelay)
            return
        }
        val page = (event.getOption(OPT_PAGE)?.asInt ?: 1).coerceAtLeast(1)
        val paged = excuseService.searchApproved(guildId, query, page, EXCUSES_PER_PAGE)
        sendPagedListing(
            event = event,
            paged = paged,
            scope = SCOPE_SEARCH,
            query = query,
            guildId = guildId,
            deleteDelay = deleteDelay,
        )
    }

    private fun handleApprove(
        event: SlashCommandInteractionEvent,
        requesterDto: UserDto,
        deleteDelay: Int,
    ) {
        if (!requesterDto.superUser) {
            sendErrorMessage(event, deleteDelay)
            return
        }
        val id = event.getOption(OPT_ID)?.asLong ?: run {
            event.hook.replyAndDelete("Provide the excuse id to approve.", deleteDelay)
            return
        }
        val existing = excuseService.getExcuseById(id) ?: run {
            event.hook.replyAndDelete("No excuse exists with id $id.", deleteDelay)
            return
        }
        if (existing.approved) {
            event.hook.replyAndDelete(EXISTING_EXCUSE_MESSAGE, deleteDelay)
            return
        }
        val approved = excuseService.approveExcuse(id)
        event.hook.replyAndDelete("Approved excuse '${approved?.excuse}'.", deleteDelay)
    }

    private fun handleDelete(
        event: SlashCommandInteractionEvent,
        requesterDto: UserDto,
        deleteDelay: Int,
    ) {
        val id = event.getOption(OPT_ID)?.asLong ?: run {
            event.hook.replyAndDelete("Provide the excuse id to delete.", deleteDelay)
            return
        }
        val isSuper = requesterDto.superUser
        val ownsPending = !isSuper && excuseService.canRequesterDeleteOwnPending(id, event.user.idLong)
        if (!isSuper && !ownsPending) {
            sendErrorMessage(event, deleteDelay)
            return
        }
        excuseService.deleteExcuseById(id)
        event.hook.replyAndDelete("Deleted excuse with id '$id'.", deleteDelay)
    }

    private fun sendPagedListing(
        event: SlashCommandInteractionEvent,
        paged: PagedExcuses,
        scope: String,
        query: String?,
        guildId: Long,
        deleteDelay: Int,
    ) {
        if (paged.rows.isEmpty()) {
            val emptyMsg = when (scope) {
                SCOPE_PENDING -> "There are no excuses pending approval, consider submitting some."
                SCOPE_SEARCH -> "No approved excuses match '${query.orEmpty()}'."
                else -> "There are no approved excuses, consider submitting some."
            }
            event.hook.replyAndDelete(emptyMsg, deleteDelay)
            return
        }

        val embed = buildPageEmbed(paged, scope, query)

        if (paged.totalPages <= 1) {
            event.hook.replyEmbedAndDelete(embed, deleteDelay)
            return
        }

        // Stateful pagination: button-id encodes everything the dispatcher needs
        // to re-render the next/prev page without holding server state.
        val prevId = encodePageButton(scope, guildId, paged.page - 1, query)
        val nextId = encodePageButton(scope, guildId, paged.page + 1, query)

        event.hook.sendMessageEmbeds(embed).addComponents(
            ActionRow.of(
                Button.primary(prevId, "⬅️ Prev").withDisabled(!paged.hasPrev),
                Button.secondary("$BUTTON_NAME:noop", "Page ${paged.page}/${paged.totalPages}").withDisabled(true),
                Button.primary(nextId, "Next ➡️").withDisabled(!paged.hasNext),
            )
        ).queue()
    }

    companion object {
        const val EXISTING_EXCUSE_MESSAGE = "I've heard that one before, keep up."
        const val MAX_EXCUSE_LENGTH = 200
        const val EXCUSES_PER_PAGE = 10
        const val BUTTON_NAME = "excuse-page"

        const val RANDOM = "random"
        const val SUBMIT = "submit"
        const val LIST = "list"
        const val SEARCH = "search"
        const val APPROVE = "approve"
        const val DELETE = "delete"

        const val SCOPE_APPROVED = "approved"
        const val SCOPE_PENDING = "pending"
        const val SCOPE_SEARCH = "search"

        private const val OPT_TEXT = "text"
        private const val OPT_AUTHOR = "author"
        private const val OPT_SCOPE = "scope"
        private const val OPT_PAGE = "page"
        private const val OPT_QUERY = "query"
        private const val OPT_ID = "id"

        fun buildPageEmbed(paged: PagedExcuses, scope: String, query: String?): net.dv8tion.jda.api.entities.MessageEmbed {
            val title = when (scope) {
                SCOPE_PENDING -> "Pending excuses"
                SCOPE_SEARCH -> "Search results for '${query.orEmpty()}'"
                else -> "Approved excuses"
            }
            val builder = EmbedBuilder()
                .setTitle(title)
                .setColor(Color(88, 101, 242))
            paged.rows.forEach { row ->
                builder.addField(
                    "#${row.id} — ${row.author ?: "Unknown"}",
                    row.excuse.orEmpty().ifBlank { "(no text)" },
                    false,
                )
            }
            builder.setFooter("Page ${paged.page} / ${paged.totalPages} · ${paged.totalCount} total")
            return builder.build()
        }

        /**
         * Encode a page button id. Query is base64'd to survive `:` separators
         * and Discord's 100-char id limit (truncation here means the button
         * silently rebuilds with a clipped query — better than the dispatcher
         * mis-parsing). Decode with [decodePageButton].
         */
        fun encodePageButton(scope: String, guildId: Long, page: Int, query: String?): String {
            val qPart = query?.let {
                java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(it.toByteArray(Charsets.UTF_8))
            }.orEmpty()
            return "$BUTTON_NAME:$scope:$guildId:$page:$qPart"
        }

        fun decodePageButton(componentId: String): DecodedPageButton? {
            val parts = componentId.split(":", limit = 5)
            if (parts.size < 4 || parts[0] != BUTTON_NAME) return null
            val scope = parts[1]
            val guildId = parts[2].toLongOrNull() ?: return null
            val page = parts[3].toIntOrNull() ?: return null
            val query = parts.getOrNull(4)?.takeIf { it.isNotEmpty() }?.let {
                runCatching { String(java.util.Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }.getOrNull()
            }
            return DecodedPageButton(scope, guildId, page, query)
        }
    }

    data class DecodedPageButton(
        val scope: String,
        val guildId: Long,
        val page: Int,
        val query: String?,
    )
}
