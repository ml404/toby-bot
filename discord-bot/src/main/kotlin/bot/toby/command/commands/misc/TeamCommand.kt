package bot.toby.command.commands.misc

import database.dto.UserDto
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import bot.toby.command.CommandContext
import bot.toby.command.ICommand.Companion.invokeDeleteOnMessageResponse

class TeamCommand : IMiscCommand {
    private val TEAM_MEMBERS = "members"
    private val TEAM_SIZE = "size"
    private val CLEANUP = "cleanup"

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()
        cleanupTemporaryChannels(event.guild!!.channels)

        val args = event.options
        if (args.isEmpty()) {
            event.hook.sendMessage(description).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return
        }
        if (event.getOption(CLEANUP)?.asBoolean == true) {
            return
        }

        val mentionedMembers = event.getOption(TEAM_MEMBERS)?.mentions?.members ?: emptyList()
        val listsToInitialize = minOf(event.getOption(TEAM_SIZE)?.asInt ?: 2, mentionedMembers.size)
        val teams = split(mentionedMembers, listsToInitialize)
        val guild = event.guild!!

        val sb = StringBuilder()
        teams.forEachIndexed { index, team ->
            val teamName = "Team ${index + 1}"
            sb.append("**$teamName**: ${team.joinToString { it!!.effectiveName }}\n")

            val createdVoiceChannel = guild.createVoiceChannel(teamName).setBitrate(guild.maxBitrate).complete()
            team.forEach { target -> guild.moveVoiceMember(target!!, createdVoiceChannel).queue() }
        }

        event.hook.sendMessage(sb.toString()).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    private fun cleanupTemporaryChannels(channels: List<GuildChannel>) {
        channels.filter { it.name.matches("(?i)team\\s[0-9]+".toRegex()) }
            .forEach { it.delete().queue() }
    }

    override val name: String = "team"
    override val description: String = "Return X teams from a list of tagged users."
    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.STRING, TEAM_MEMBERS, "Which discord users would you like to split into the teams?", true),
        OptionData(OptionType.INTEGER, TEAM_SIZE, "Number of teams you want to split members into (defaults to 2)"),
        OptionData(OptionType.BOOLEAN, CLEANUP, "Do you want to perform cleanup to reset the temporary channels in the guild?")
    )

    companion object {
        fun split(list: List<Member?>, splitSize: Int): List<List<Member?>> {
            val shuffledList = list.shuffled()
            val result = mutableListOf<List<Member?>>()
            val numberOfMembersTagged = list.size
            repeat(splitSize) { i ->
                val sizeOfTeams = numberOfMembersTagged / splitSize
                val fromIndex = i * sizeOfTeams
                val toIndex = (i + 1) * sizeOfTeams
                result.add(shuffledList.subList(fromIndex, toIndex))
            }
            return result
        }
    }
}
