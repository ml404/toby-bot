package bot.toby.command.commands.misc

import bot.toby.modal.modals.TeamSplitModal
import core.command.Command.Companion.replyAndDelete
import core.command.CommandContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.modals.Modal
import org.springframework.stereotype.Component
import java.awt.Color
import java.util.UUID

@Component
class TeamCommand : MiscCommand {

    override val name: String = "team"
    override val description: String = "Split a roster into random teams and move members into per-team voice channels."

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData(SUB_SPLIT, "Open a form to split a roster into random teams.")
            .addOptions(
                OptionData(
                    OptionType.STRING,
                    OPT_MEMBERS,
                    "Optional: pre-fill the form with these @-mentioned members.",
                    false,
                )
            ),
        SubcommandData(SUB_CLEANUP, "Delete the temporary 'Team N' voice channels this command created."),
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        if (event.guild == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue()
            return
        }

        when (event.subcommandName) {
            SUB_SPLIT -> {
                val prefill = event.getOption(OPT_MEMBERS)?.mentions?.members.orEmpty()
                    .filter { !it.user.isBot }
                event.replyModal(buildSplitModal(prefill)).queue()
            }
            SUB_CLEANUP -> {
                event.deferReply().queue()
                val deleted = cleanupTemporaryChannels(event.guild!!.channels)
                event.hook.replyAndDelete(
                    if (deleted == 0) "No temporary team channels to clean up."
                    else "Cleaned up $deleted temporary team channel${if (deleted == 1) "" else "s"}.",
                    deleteDelay,
                )
            }
            else -> {
                event.deferReply().queue()
                event.hook.replyAndDelete(
                    "Use `/team split` to open the team-split form, or `/team cleanup` to remove the temp channels.",
                    deleteDelay,
                )
            }
        }
    }

    private fun buildSplitModal(prefilledMembers: List<Member>): Modal {
        val builder = Modal.create(TeamSplitModal.MODAL_NAME, "Split a roster into teams")

        val presetField = TextInput.create(TeamSplitModal.FIELD_PRESET_NAME, TextInputStyle.SHORT)
            .setPlaceholder("e.g. friday-night (optional)")
            .setRequired(false)
            .setMaxLength(PRESET_NAME_MAX)
            .build()
        builder.addComponents(Label.of("Load preset (optional)", presetField))

        val membersBuilder = TextInput.create(TeamSplitModal.FIELD_MEMBERS, TextInputStyle.PARAGRAPH)
            .setPlaceholder("@mentions or raw IDs. Names in parentheses are decorative.")
            .setRequired(false)
            .setMaxLength(MEMBERS_FIELD_MAX)
        if (prefilledMembers.isNotEmpty()) {
            // Modal text inputs render raw text, not Discord mentions, so a bare
            // `<@123…>` string is unreadable. Prefix each id with the member's
            // effective name so the user can spot wrong people / typos at a
            // glance. The parser only looks at the `<@id>` token, so the name
            // is decorative — users can remove a whole `Name (<@id>)` block to
            // drop someone, and adding a new member still requires the raw id.
            membersBuilder.setValue(
                prefilledMembers.joinToString(", ") { "${it.effectiveName} (<@${it.idLong}>)" }
            )
        }
        builder.addComponents(Label.of("Members", membersBuilder.build()))

        val teamCountField = TextInput.create(TeamSplitModal.FIELD_TEAM_COUNT, TextInputStyle.SHORT)
            .setPlaceholder("2")
            .setValue(TeamSplitModal.DEFAULT_TEAM_COUNT.toString())
            .setRequired(true)
            .setMaxLength(3)
            .build()
        builder.addComponents(Label.of("Number of teams", teamCountField))

        val strategyField = TextInput.create(TeamSplitModal.FIELD_NAME_STRATEGY, TextInputStyle.SHORT)
            .setPlaceholder("prefix or list")
            .setValue(TeamSplitModal.STRATEGY_PREFIX)
            .setRequired(false)
            .setMaxLength(8)
            .build()
        builder.addComponents(Label.of("Name strategy: prefix | list", strategyField))

        val namesField = TextInput.create(TeamSplitModal.FIELD_NAMES, TextInputStyle.SHORT)
            .setPlaceholder("'Squad' (prefix mode) or 'Red,Blue,Green' (list mode)")
            .setValue(TeamSplitModal.DEFAULT_PREFIX)
            .setRequired(false)
            .setMaxLength(NAMES_FIELD_MAX)
            .build()
        builder.addComponents(Label.of("Team names", namesField))

        return builder.build()
    }

    private fun cleanupTemporaryChannels(channels: List<GuildChannel>): Int {
        val matching = channels.filter { TEAM_CHANNEL_PATTERN.containsMatchIn(it.name) }
        matching.forEach { it.delete().queue() }
        return matching.size
    }

    companion object {
        const val SUB_SPLIT = "split"
        const val SUB_CLEANUP = "cleanup"
        private const val OPT_MEMBERS = "members"

        const val BUTTON_CONFIRM = "team-confirm"
        const val BUTTON_REROLL = "team-reroll"
        const val BUTTON_CANCEL = "team-cancel"

        private const val PRESET_NAME_MAX = 64
        private const val MEMBERS_FIELD_MAX = 4000
        private const val NAMES_FIELD_MAX = 200
        private const val PREVIEW_COLOR_RGB = 0x5865F2 // Discord blurple
        private const val RESULT_COLOR_RGB = 0x57F287  // Discord green

        // Match the legacy "Team N" channel format used by older invocations
        // as well as the new "<prefix> N" channels created by Confirm. We
        // can't enumerate every possible custom prefix, so cleanup stays
        // conservative: match only the default "Team N" pattern. Custom-named
        // channels stay until manually deleted (intentional — users named
        // them, users own them).
        private val TEAM_CHANNEL_PATTERN = Regex("(?i)^team\\s+\\d+$")

        /**
         * Legacy random-split helper. Returned by JDA `List<Member>` in
         * the new flow, but tests still build the input out of nullable
         * members so we keep the `List<Member?>` signature for source
         * compatibility.
         */
        fun split(list: List<Member?>, splitSize: Int): List<List<Member?>> {
            if (splitSize <= 0) return emptyList()
            val shuffledList = list.shuffled()
            val total = list.size
            val baseSize = total / splitSize
            val remainder = total % splitSize
            val result = mutableListOf<List<Member?>>()
            var cursor = 0
            repeat(splitSize) { i ->
                // Distribute the remainder across the first `remainder` teams
                // so we don't drop members on the floor when total %
                // splitSize != 0 (the old impl silently truncated).
                val take = baseSize + if (i < remainder) 1 else 0
                result.add(shuffledList.subList(cursor, cursor + take))
                cursor += take
            }
            return result
        }

        fun buildPreviewEmbed(
            guild: Guild,
            teamNames: List<String>,
            assignments: List<List<Long>>,
        ): net.dv8tion.jda.api.entities.MessageEmbed {
            val builder = EmbedBuilder()
                .setTitle("Team split preview")
                .setColor(Color(PREVIEW_COLOR_RGB))
                .setDescription("Click **Confirm** to create voice channels and move members, **Reroll** to re-shuffle, or **Cancel**.")
            renderTeamFields(builder, guild, teamNames, assignments)
            builder.setFooter("Preview only — no channels created yet.")
            return builder.build()
        }

        fun buildResultEmbed(
            guild: Guild,
            teamNames: List<String>,
            assignments: List<List<Long>>,
        ): net.dv8tion.jda.api.entities.MessageEmbed {
            val builder = EmbedBuilder()
                .setTitle("Teams created")
                .setColor(Color(RESULT_COLOR_RGB))
            renderTeamFields(builder, guild, teamNames, assignments)
            builder.setFooter("Use /team cleanup to remove the temporary channels.")
            return builder.build()
        }

        private fun renderTeamFields(
            builder: EmbedBuilder,
            guild: Guild,
            teamNames: List<String>,
            assignments: List<List<Long>>,
        ) {
            assignments.forEachIndexed { index, memberIds ->
                val label = teamNames.getOrNull(index) ?: "Team ${index + 1}"
                val rendered = memberIds.joinToString("\n") { id ->
                    val name = guild.getMemberById(id)?.effectiveName
                        ?: guild.jda.getUserById(id)?.name
                        ?: "Unknown ($id)"
                    "• $name"
                }.ifEmpty { "(empty)" }
                builder.addField("$label · ${memberIds.size}", rendered, true)
            }
        }

        fun buildActionRow(sessionId: UUID): ActionRow = ActionRow.of(
            Button.success("$BUTTON_CONFIRM:$sessionId", "Confirm"),
            Button.primary("$BUTTON_REROLL:$sessionId", "Reroll"),
            Button.danger("$BUTTON_CANCEL:$sessionId", "Cancel"),
        )

        fun buildDisabledActionRow(sessionId: UUID): ActionRow = ActionRow.of(
            Button.success("$BUTTON_CONFIRM:$sessionId", "Confirm").withDisabled(true),
            Button.primary("$BUTTON_REROLL:$sessionId", "Reroll").withDisabled(true),
            Button.danger("$BUTTON_CANCEL:$sessionId", "Cancel").withDisabled(true),
        )
    }
}
