package bot.toby.modal.modals

import bot.toby.command.commands.misc.TeamCommand
import core.modal.Modal
import core.modal.ModalContext
import database.service.TeamPresetService
import database.service.TeamSplitSessionService
import org.springframework.stereotype.Component

/**
 * Submission handler for the `/team split` form.
 *
 * Discord caps modals at 5 components, so we share one paragraph field
 * for members across both "pasted in" and "loaded from preset" cases:
 * the preset's roster is unioned with whatever the user typed, and the
 * combined list is what we split.
 *
 * Voice channels are NOT created here — this handler only persists a
 * preview session and posts an ephemeral embed with Confirm / Reroll /
 * Cancel buttons. The actual side-effects happen in [TeamConfirmButton].
 */
@Component
class TeamSplitModal(
    private val teamPresetService: TeamPresetService,
    private val teamSplitSessionService: TeamSplitSessionService,
) : Modal {
    override val name = MODAL_NAME

    override fun handle(ctx: ModalContext, deleteDelay: Int) {
        val event = ctx.event
        val guild = ctx.guild
        val guildId = guild.idLong

        val presetName = event.getValue(FIELD_PRESET_NAME)?.asString?.trim().orEmpty()
        val pastedMembersRaw = event.getValue(FIELD_MEMBERS)?.asString.orEmpty()
        val teamCountStr = event.getValue(FIELD_TEAM_COUNT)?.asString?.trim().orEmpty()
        val nameStrategy = event.getValue(FIELD_NAME_STRATEGY)?.asString?.trim()?.lowercase().orEmpty()
        val namesRaw = event.getValue(FIELD_NAMES)?.asString?.trim().orEmpty()

        val presetIds: List<Long> = if (presetName.isNotEmpty()) {
            val preset = teamPresetService.getByName(guildId, presetName) ?: run {
                event.hook.sendMessage("No preset named '$presetName' in this server.")
                    .setEphemeral(true).queue()
                return
            }
            preset.memberIdList
        } else emptyList()

        val pastedIds = parseMemberIds(pastedMembersRaw)
        val combinedIds = (presetIds + pastedIds).distinct()

        if (combinedIds.size < 2) {
            event.hook.sendMessage("Need at least 2 members to split into teams.")
                .setEphemeral(true).queue()
            return
        }

        val resolvedMembers = combinedIds.mapNotNull { id -> guild.getMemberById(id) }
            .filter { !it.user.isBot }
        if (resolvedMembers.size < 2) {
            event.hook.sendMessage(
                "Could not resolve at least 2 valid members in this server from the supplied list."
            ).setEphemeral(true).queue()
            return
        }

        val teamCount = teamCountStr.toIntOrNull() ?: DEFAULT_TEAM_COUNT
        if (teamCount < 2) {
            event.hook.sendMessage("Team count must be at least 2.").setEphemeral(true).queue()
            return
        }
        if (teamCount > resolvedMembers.size) {
            event.hook.sendMessage(
                "Team count ($teamCount) is larger than the member count (${resolvedMembers.size})."
            ).setEphemeral(true).queue()
            return
        }

        val teamNames = resolveTeamNames(
            strategy = nameStrategy.ifEmpty { STRATEGY_PREFIX },
            namesField = namesRaw,
            teamCount = teamCount,
        ) ?: run {
            event.hook.sendMessage(
                "Name list has the wrong number of entries — got ${
                    namesRaw.split(',').count { it.isNotBlank() }
                }, expected $teamCount."
            ).setEphemeral(true).queue()
            return
        }

        val resolvedMemberIds = resolvedMembers.map { it.idLong }
        val assignments = TeamCommand.split(resolvedMembers, teamCount)
            .map { team -> team.mapNotNull { it?.idLong } }

        val session = teamSplitSessionService.createSession(
            guildId = guildId,
            requesterDiscordId = event.user.idLong,
            memberIds = resolvedMemberIds,
            teamCount = teamCount,
            assignments = assignments,
            teamNames = teamNames,
        )

        val embed = TeamCommand.buildPreviewEmbed(guild, teamNames, assignments)
        event.hook.sendMessageEmbeds(embed)
            .addComponents(TeamCommand.buildActionRow(session.id))
            .setEphemeral(true)
            .queue()
    }

    private fun resolveTeamNames(strategy: String, namesField: String, teamCount: Int): List<String>? {
        return when (strategy) {
            STRATEGY_LIST -> {
                val parsed = namesField.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (parsed.size != teamCount) null else parsed
            }
            else -> {
                val prefix = namesField.ifEmpty { DEFAULT_PREFIX }
                (1..teamCount).map { "$prefix $it" }
            }
        }
    }

    companion object {
        const val MODAL_NAME = "team_split"
        const val FIELD_PRESET_NAME = "preset_name"
        const val FIELD_MEMBERS = "members"
        const val FIELD_TEAM_COUNT = "team_count"
        const val FIELD_NAME_STRATEGY = "name_strategy"
        const val FIELD_NAMES = "names"

        const val STRATEGY_PREFIX = "prefix"
        const val STRATEGY_LIST = "list"
        const val DEFAULT_PREFIX = "Team"
        const val DEFAULT_TEAM_COUNT = 2

        // Accepts `<@123>`, `<@!123>`, and bare snowflakes.
        // Snowflakes today are 17-20 digits; the regex tolerates the range.
        private val MENTION_PATTERN = Regex("""<@!?(\d{15,20})>|(\b\d{15,20}\b)""")

        fun parseMemberIds(raw: String): List<Long> {
            if (raw.isBlank()) return emptyList()
            val ids = mutableListOf<Long>()
            for (match in MENTION_PATTERN.findAll(raw)) {
                val id = (match.groupValues[1].ifEmpty { match.groupValues[2] }).toLongOrNull()
                if (id != null) ids.add(id)
            }
            return ids.distinct()
        }
    }
}
