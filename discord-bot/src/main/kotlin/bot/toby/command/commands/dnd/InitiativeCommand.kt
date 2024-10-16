package bot.toby.command.commands.dnd

import bot.toby.command.CommandContext
import bot.toby.command.ICommand.Companion.invokeDeleteOnHookResponse
import bot.toby.helpers.DnDHelper
import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class InitiativeCommand @Autowired constructor(private val dndHelper: DnDHelper) : IDnDCommand {

    override fun handle(ctx: CommandContext, requestingUserDto: bot.database.dto.UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()
        val member = ctx.member
        val names = event.getOption("names")?.asString
        val dm = event.getOption("dm")?.asMember ?: ctx.member
        val voiceState = member?.voiceState
        val channelOption = event.getOption("channel")
        val memberList = getMemberList(voiceState, channelOption)
        val nameList = getNameList(names)
        val initiativeMap = mutableMapOf<String, Int>()

        if (invalidArguments(deleteDelay, event, memberList, nameList)) return

        dndHelper.clearInitiative()
        if (nameList.isNotEmpty()) {
            dndHelper.rollInitiativeForString(nameList, initiativeMap)
        } else {
            dndHelper.rollInitiativeForMembers(memberList, dm!!, initiativeMap)
        }

        if (checkForNonDmMembersInVoiceChannel(deleteDelay, event)) return
        displayAllValues(event.hook, deleteDelay)
    }

    override val name: String = "initiative"
    override val description: String = "Roll initiative for members in voice channel. DM is excluded from roll."
    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.MENTIONABLE, "dm", "Who is the DM? default: caller of command."),
        OptionData(
            OptionType.CHANNEL,
            "channel",
            "which channel is initiative being rolled for? default: voice channel of user calling this."
        ),
        OptionData(OptionType.STRING, "names", "to be used as an alternative for the channel option. Comma delimited.")
    )

    private fun invalidArguments(
        deleteDelay: Int?,
        event: SlashCommandInteractionEvent,
        memberList: List<Member>,
        nameList: List<String>
    ): Boolean {
        if (memberList.isEmpty() && nameList.isEmpty()) {
            event.reply("You must either be in a voice channel when using this command, or tag a voice channel in the channel option with people in it, or give a list of names to roll for.")
                .setEphemeral(true)
                .queue(invokeDeleteOnHookResponse(deleteDelay ?: 0))
            return true
        }
        return false
    }

    private fun checkForNonDmMembersInVoiceChannel(deleteDelay: Int?, event: SlashCommandInteractionEvent): Boolean {
        if (dndHelper.sortedEntries.isEmpty()) {
            event.reply("The amount of non DM members in the voice channel you're in, or the one you mentioned, is empty, so no rolls were done.")
                .setEphemeral(true)
                .queue(invokeDeleteOnHookResponse(deleteDelay ?: 0))
            return true
        }
        return false
    }

    private fun getMemberList(voiceState: GuildVoiceState?, channelOption: OptionMapping?): List<Member> {
        val audioChannelUnion = voiceState?.channel
        val channelMembers = channelOption?.asChannel?.asAudioChannel()?.members
        return audioChannelUnion?.members ?: channelMembers ?: emptyList()
    }

    private fun getNameList(names: String?): List<String> {
        return names?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }

    private fun displayAllValues(hook: InteractionHook?, deleteDelay: Int?) {
        val embedBuilder = dndHelper.initiativeEmbedBuilder
        dndHelper.sendOrEditInitiativeMessage(hook ?: return, embedBuilder, null, deleteDelay ?: 0)
    }

}
