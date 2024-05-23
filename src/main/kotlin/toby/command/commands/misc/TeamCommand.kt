package toby.command.commands.misc

import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.jpa.dto.UserDto
import java.util.*
import java.util.function.Consumer
import java.util.stream.Collectors
import kotlin.math.min

class TeamCommand : IMiscCommand {
    private val TEAM_MEMBERS = "members"
    private val TEAM_SIZE = "size"
    private val CLEANUP = "cleanup"
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()
        cleanupTemporaryChannels(event.guild!!.channels)
        val args = event.options
        if (Optional.ofNullable(event.getOption(CLEANUP)).map { obj: OptionMapping -> obj.getAsBoolean() }.orElse(false)) {
            return
        }
        if (args.isEmpty()) {
            event.hook.sendMessage(description).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return
        }
        //Shuffle gives an NPE with default return of message.getMentionedMembers()
        val mentionedMembers = Optional.ofNullable(event.getOption(TEAM_MEMBERS)).map { obj: OptionMapping -> obj.mentions }.map { obj: Mentions -> obj.members }.orElse(emptyList<Member>())
        var listsToInitialise = Optional.ofNullable(event.getOption(TEAM_SIZE)).map { obj: OptionMapping -> obj.asInt }.orElse(2)
        listsToInitialise = min(listsToInitialise.toDouble(), mentionedMembers.size.toDouble()).toInt()
        val teams = split(mentionedMembers, listsToInitialise)
        val sb = StringBuilder()
        val guild = event.guild!!
        for (i in teams.indices) {
            val teamName = String.format("Team %d", i + 1)
            sb.append(String.format("**%s**: %s \n", teamName, teams[i].stream().map { obj: Member? -> obj!!.effectiveName }.collect(Collectors.joining(", "))))
            val voiceChannel = guild.createVoiceChannel(teamName)
            val createdVoiceChannel = voiceChannel.setBitrate(guild.getMaxBitrate()).complete()
            teams[i].forEach(Consumer { target: Member? ->
                guild.moveVoiceMember(target!!, createdVoiceChannel)
                        .queue({ event.hook.sendMessageFormat("Moved %s to '%s'", target.effectiveName, createdVoiceChannel.name).queue(invokeDeleteOnMessageResponse(deleteDelay!!)) }
                        ) { error: Throwable -> event.hook.sendMessageFormat("Could not move '%s'", error.message).queue(invokeDeleteOnMessageResponse(deleteDelay!!)) }
            })
        }
        event.hook.sendMessage(sb.toString()).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    private fun cleanupTemporaryChannels(channels: List<GuildChannel>) {
        channels.stream()
                .filter { guildChannel: GuildChannel -> guildChannel.name.matches("(?i)team\\s[0-9]+".toRegex()) }
                .forEach { guildChannel: GuildChannel -> guildChannel.delete().queue() }
    }

    override val name: String
        get() = "team"
    override val description: String
        get() = "Return X teams from a list of tagged users."
    override val optionData: List<OptionData>
        get() = listOf(
                OptionData(OptionType.STRING, TEAM_MEMBERS, "Which discord users would you like to split into the teams?", true),
                OptionData(OptionType.INTEGER, TEAM_SIZE, "Number of teams you want to split members into (defaults to 2)"),
                OptionData(OptionType.BOOLEAN, CLEANUP, "Do you want to perform cleanup to reset the temporary channels in the guild?")
        )

    companion object {
        fun split(list: List<Member?>, splitSize: Int): List<List<Member?>> {
            val result: MutableList<List<Member?>> = ArrayList()
            Collections.shuffle(list)
            val numberOfMembersTagged = list.size
            for (i in 0 until splitSize) {
                val sizeOfTeams = numberOfMembersTagged / splitSize
                val fromIndex = i * sizeOfTeams
                val toIndex = (i + 1) * sizeOfTeams
                result.add(ArrayList(list.subList(fromIndex, toIndex)))
            }
            return result
        }
    }
}
