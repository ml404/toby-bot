package toby.command.commands.dnd

import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnHookResponse
import toby.helpers.DnDHelper
import toby.jpa.dto.UserDto
import toby.jpa.service.IUserService
import java.util.*

class InitiativeCommand(private val userService: IUserService) : IDnDCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()
        val member = ctx.member
        val namesOptional = event.getOption("names")?.asString
        val dm = event.getOption("dm")?.getAsMember() ?: ctx.member
        val voiceState = Optional.ofNullable(member!!.voiceState)
        val channelOptional = Optional.ofNullable(event.getOption("channel"))
        val memberList = getMemberList(voiceState, channelOptional)
        val nameList = getNameList(namesOptional)
        val initiativeMap: MutableMap<String, Int> = mutableMapOf()
        if (validateArguments(deleteDelay, event, memberList, nameList)) return

        //If we are calling this a second time, it's better to clean slate the DnDHelper for that guild.
        val hook = event.hook
        DnDHelper.clearInitiative()
        if (nameList.isNotEmpty()) {
            DnDHelper.rollInitiativeForString(nameList, initiativeMap)
        } else {
            DnDHelper.rollInitiativeForMembers(memberList, dm!!, initiativeMap, userService)
        }
        if (checkForNonDmMembersInVoiceChannel(deleteDelay, event)) return
        displayAllValues(hook, deleteDelay)
    }

    override val name: String
        get() = "initiative"
    override val description: String
        get() = "Roll initiative for members in voice channel. DM is excluded from roll."
    override val optionData: List<OptionData>
        get() {
            val dm = OptionData(OptionType.MENTIONABLE, "dm", "Who is the DM? default: caller of command.")
            val voiceChannel = OptionData(OptionType.CHANNEL, "channel", "which channel is initiative being rolled for? default: voice channel of user calling this.")
            val nameStrings = OptionData(OptionType.STRING, "names", "to be used as an alternative for the channel option. Comma delimited.")
            return listOf(dm, voiceChannel, nameStrings)
        }

    companion object {
        private fun validateArguments(deleteDelay: Int?, event: SlashCommandInteractionEvent, memberList: List<Member>, nameList: List<String>): Boolean {
            if (memberList.isEmpty() && nameList.isEmpty()) {
                event
                        .reply("You must either be in a voice channel when using this command, or tag a voice channel in the channel option with people in it, or give a list of names to roll for.")
                        .setEphemeral(true)
                        .queue(invokeDeleteOnHookResponse(deleteDelay!!))
                return true
            }
            return false
        }

        private fun checkForNonDmMembersInVoiceChannel(deleteDelay: Int?, event: SlashCommandInteractionEvent): Boolean {
            if (DnDHelper.sortedEntries.isEmpty()) {
                event
                        .reply("The amount of non DM members in the voice channel you're in, or the one you mentioned, is empty, so no rolls were done.")
                        .setEphemeral(true)
                        .queue(invokeDeleteOnHookResponse(deleteDelay!!))
                return true
            }
            return false
        }

        private fun getMemberList(voiceState: Optional<GuildVoiceState>, channelOptional: Optional<OptionMapping>): List<Member> {
            val audioChannelUnion = voiceState.map { obj: GuildVoiceState -> obj.channel }
            return channelOptional
                    .map { optionMapping: OptionMapping -> optionMapping.getAsChannel().asAudioChannel().members }
                    .orElseGet { audioChannelUnion.map { obj: AudioChannelUnion? -> obj!!.members }.orElse(emptyList()) }
        }

        private fun getNameList(names: String?): List<String> {
            return names?.trim()
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
        }

        fun displayAllValues(hook: InteractionHook?, deleteDelay: Int?) {
            val embedBuilder = DnDHelper.initiativeEmbedBuilder
            DnDHelper.sendOrEditInitiativeMessage(hook!!, embedBuilder, null, deleteDelay)
        }
    }
}
