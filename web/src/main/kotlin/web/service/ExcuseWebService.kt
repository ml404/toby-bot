package web.service

import database.dto.ExcuseDto
import database.service.social.ExcuseService
import database.service.social.PagedExcuses
import database.service.user.UserService
import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ExcuseWebService(
    private val excuseService: ExcuseService,
    private val userService: UserService,
    private val jda: JDA,
    private val introWebService: IntroWebService,
) {
    companion object {
        const val PAGE_SIZE = 12
        const val MAX_EXCUSE_LENGTH = 200
        const val TAB_APPROVED = "approved"
        const val TAB_PENDING = "pending"
    }

    // Reuse Intros' Discord-API guild fetch — both pages need the same
    // /users/@me/guilds → mutual-guild filter and the same 60s cache.
    fun getMutualGuilds(accessToken: String): List<GuildInfo> =
        introWebService.getMutualGuilds(accessToken)

    fun getGuildName(guildId: Long): String? = jda.getGuildById(guildId)?.name

    fun isSuperUser(discordId: Long, guildId: Long): Boolean =
        userService.getUserById(discordId, guildId)?.superUser == true

    fun getGuildMembers(guildId: Long): List<MemberInfo> {
        val guild = jda.getGuildById(guildId) ?: return emptyList()
        return guild.members
            .filter { !it.user.isBot }
            .map { MemberInfo(it.id, it.effectiveName, it.effectiveAvatarUrl) }
            .sortedBy { it.name.lowercase() }
    }

    fun getApprovedCountsForGuilds(guildIds: List<Long>): Map<Long, Int> =
        guildIds.associateWith { excuseService.countApproved(it).toInt() }

    /**
     * Return a page of excuses for the given guild + tab + (optional) query.
     * The pending tab is gated to superusers; non-superusers asking for
     * pending are silently downgraded to the approved tab, with
     * [ExcusePageViewModel.requestedTab] set to "approved" so the template
     * doesn't show stale tab state.
     */
    fun getPage(
        guildId: Long,
        tab: String,
        query: String?,
        page: Int,
        requesterDiscordId: Long,
    ): ExcusePageViewModel {
        val isSuper = isSuperUser(requesterDiscordId, guildId)
        val effectiveTab = if (tab == TAB_PENDING && !isSuper) TAB_APPROVED else tab

        val paged: PagedExcuses = when {
            effectiveTab == TAB_PENDING -> excuseService.listPendingPaged(guildId, page, PAGE_SIZE)
            !query.isNullOrBlank() -> excuseService.searchApproved(guildId, query.trim(), page, PAGE_SIZE)
            else -> excuseService.listApprovedPaged(guildId, page, PAGE_SIZE)
        }

        val rows = paged.rows.map { it.toRowViewModel(guildId, requesterDiscordId, isSuper) }

        return ExcusePageViewModel(
            rows = rows,
            page = paged.page,
            totalPages = paged.totalPages,
            totalCount = paged.totalCount,
            isSuperUser = isSuper,
            requestedTab = effectiveTab,
            query = query?.trim(),
        )
    }

    fun getRandomApproved(guildId: Long): RandomExcuseViewModel? {
        val pick = excuseService.listApprovedGuildExcuses(guildId).filterNotNull().randomOrNull()
            ?: return null
        return RandomExcuseViewModel(
            id = pick.id ?: 0L,
            text = pick.excuse.orEmpty(),
            author = resolveDisplayAuthor(guildId, pick),
        )
    }

    /**
     * Author rendering ladder: prefer the current guild-member effective name
     * (picks up nickname changes), fall back to the cached Discord user name
     * (member left the guild), then the snapshot recorded at submission
     * (legacy rows from before authorDiscordId was a column), then a generic
     * placeholder if everything is null. Mirrors ExcuseCommand.resolveDisplayAuthor.
     */
    private fun resolveDisplayAuthor(guildId: Long, row: ExcuseDto): String {
        val authorId = row.authorDiscordId
        if (authorId != null) {
            val current = jda.getGuildById(guildId)?.getMemberById(authorId)
                ?.effectiveName?.takeIf { it.isNotBlank() }
                ?: jda.getUserById(authorId)?.name?.takeIf { it.isNotBlank() }
            if (current != null) return current
        }
        return row.author?.takeIf { it.isNotBlank() } ?: "Unknown"
    }

    /**
     * Submit a new pending excuse. Author override is honoured only when
     * the requester is a superuser.
     */
    fun submit(
        guildId: Long,
        text: String,
        authorOverrideDiscordId: Long?,
        requesterDiscordId: Long,
    ): SubmitResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return SubmitResult(error = "Provide some excuse text.")
        if (trimmed.length > MAX_EXCUSE_LENGTH) {
            return SubmitResult(error = "Excuse is too long (max $MAX_EXCUSE_LENGTH characters).")
        }

        val isSuper = isSuperUser(requesterDiscordId, guildId)
        val authorDiscordId = if (isSuper) authorOverrideDiscordId ?: requesterDiscordId else requesterDiscordId
        val guild = jda.getGuildById(guildId)
        val authorName = guild?.getMemberById(authorDiscordId)?.effectiveName
            ?: jda.getUserById(authorDiscordId)?.name
            ?: "Unknown"

        val existing = excuseService.listAllGuildExcuses(guildId)
            .filterNotNull()
            .firstOrNull { it.excuse.equals(trimmed, ignoreCase = true) }
        if (existing != null) return SubmitResult(error = "That excuse already exists for this server.")

        val saved = excuseService.createNewExcuse(
            ExcuseDto(
                guildId = guildId,
                author = authorName,
                excuse = trimmed,
                approved = false,
                authorDiscordId = authorDiscordId,
            )
        )
        return SubmitResult(id = saved?.id)
    }

    fun approve(id: Long, requesterDiscordId: Long, guildId: Long): String? {
        if (!isSuperUser(requesterDiscordId, guildId)) return "You don't have permission to approve excuses."
        val existing = excuseService.getExcuseById(id) ?: return "Excuse not found."
        if (existing.guildId != guildId) return "Excuse not found."
        if (existing.approved) return null
        excuseService.approveExcuse(id) ?: return "Excuse not found."
        return null
    }

    fun delete(id: Long, requesterDiscordId: Long, guildId: Long): String? {
        val existing = excuseService.getExcuseById(id) ?: return "Excuse not found."
        if (existing.guildId != guildId) return "Excuse not found."

        val isSuper = isSuperUser(requesterDiscordId, guildId)
        val ownsPending = !existing.approved && existing.authorDiscordId == requesterDiscordId
        if (!isSuper && !ownsPending) return "You don't have permission to delete that excuse."

        excuseService.deleteExcuseById(id)
        return null
    }

    private fun ExcuseDto.toRowViewModel(
        guildId: Long,
        requesterDiscordId: Long,
        isSuper: Boolean,
    ): ExcuseRowViewModel {
        val isAuthor = authorDiscordId != null && authorDiscordId == requesterDiscordId
        val canDelete = isSuper || (!approved && isAuthor)
        val canApprove = isSuper && !approved
        return ExcuseRowViewModel(
            id = id ?: 0L,
            text = excuse.orEmpty(),
            author = resolveDisplayAuthor(guildId, this),
            approved = approved,
            createdAt = createdAt,
            approvedAt = approvedAt,
            isAuthor = isAuthor,
            canDelete = canDelete,
            canApprove = canApprove,
        )
    }

}

data class ExcuseRowViewModel(
    val id: Long,
    val text: String,
    val author: String,
    val approved: Boolean,
    val createdAt: Instant?,
    val approvedAt: Instant?,
    val isAuthor: Boolean,
    val canDelete: Boolean,
    val canApprove: Boolean,
)

data class ExcusePageViewModel(
    val rows: List<ExcuseRowViewModel>,
    val page: Int,
    val totalPages: Int,
    val totalCount: Long,
    val isSuperUser: Boolean,
    val requestedTab: String,
    val query: String?,
) {
    val hasPrev: Boolean get() = page > 1
    val hasNext: Boolean get() = page < totalPages
}

data class RandomExcuseViewModel(val id: Long, val text: String, val author: String)

data class SubmitResult(val id: Long? = null, val error: String? = null) {
    val ok: Boolean get() = error == null
}
