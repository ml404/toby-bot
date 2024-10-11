package bot.toby.command.commands.dnd

import bot.database.service.IUserService
import bot.toby.command.CommandContext
import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guildVoiceState
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.replyCallbackAction
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.CommandTest.Companion.user
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.helpers.DnDHelper
import bot.toby.helpers.UserDtoHelper
import io.mockk.*
import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion
import net.dv8tion.jda.api.interactions.Interaction
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class InitiativeCommandTest : CommandTest {
    private lateinit var initiativeCommand: InitiativeCommand
    lateinit var userService: IUserService

    private lateinit var userDtoHelper: UserDtoHelper
    private lateinit var dndHelper: DnDHelper
    private lateinit var channelOption: OptionMapping
    private lateinit var initButtons: DnDHelper.TableButtons

    @BeforeEach
    fun setup() {
        setUpCommonMocks()
        userDtoHelper = mockk()
        dndHelper = DnDHelper(userDtoHelper)
        initiativeCommand = InitiativeCommand(dndHelper)
        channelOption = mockk<OptionMapping>()
        initButtons = dndHelper.initButtons
        every { event.getOption("channel") } returns channelOption
        every { channelOption.asChannel.asAudioChannel().members } returns listOf(member)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    @Test
    fun test_initiativeCommandWithCorrectSetup_WithNames() {
        // Arrange
        val commandContext = CommandContext(event)
        val namesMapping = mockk<OptionMapping>()
        val dmOptionMapping = mockk<OptionMapping>()
        val dmMember = mockk<Member>()
        val interaction = mockk<Interaction>()

        every { namesMapping.asString } returns "name1, name2, name3, name4"
        every { dmOptionMapping.asMember } returns dmMember
        every { event.getOption("names") } returns namesMapping
        every { event.getOption("dm") } returns dmOptionMapping
        every { event.hook.interaction } returns interaction
        every { interaction.guild } returns CommandTest.guild
        every { member.user } returns user


        // Act
        initiativeCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.deferReply() }
        verify(exactly = 1) { event.hook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) {
            webhookMessageCreateAction.setActionRow(
                initButtons.prev,
                initButtons.clear,
                initButtons.next
            )
        }
    }

    @Test
    fun test_initiativeCommandWithCorrectSetup_UsingMemberVoiceState() {
        // Arrange
        val commandContext = CommandContext(event)
        val namesMapping = mockk<OptionMapping>()
        val dmOptionMapping = mockk<OptionMapping>()
        val dmMember = mockk<Member>()
        val interaction = mockk<Interaction>()

        every { namesMapping.asString } returns "name1, name2, name3, name4"
        every { dmOptionMapping.asMember } returns dmMember
        every { event.getOption("names") } returns namesMapping
        every { event.getOption("dm") } returns dmOptionMapping
        every { event.hook.interaction } returns interaction
        val guildVoiceState = mockk<GuildVoiceState>()
        val audioChannelUnion = mockk<AudioChannelUnion>()

        every { member.voiceState } returns guildVoiceState
        every { guildVoiceState.channel } returns audioChannelUnion
        every { audioChannelUnion.members } returns listOf(member)
        every { event.hook.interaction } returns interaction
        every { interaction.guild } returns CommandTest.guild
        every { member.user } returns user

        // Act
        initiativeCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.deferReply() }
        verify(exactly = 1) { event.hook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) {
            webhookMessageCreateAction.setActionRow(
                initButtons.prev,
                initButtons.clear,
                initButtons.next
            )
        }
    }

    @Test
    fun test_initiativeCommandWithNoValidChannel() {
        // Arrange
        val commandContext = CommandContext(event)
        val namesMapping = mockk<OptionMapping>()
        val dmOptionMapping = mockk<OptionMapping>()
        val dmMember = mockk<Member>()
        val interaction = mockk<Interaction>()

        every { namesMapping.asString } returns ""
        every { dmOptionMapping.asMember } returns dmMember
        every { event.getOption("names") } returns namesMapping
        every { event.getOption("dm") } returns dmOptionMapping
        every { event.hook.interaction } returns interaction
        val guildVoiceState = mockk<GuildVoiceState>()
        val audioChannelUnion = mockk<AudioChannelUnion>()
        every { member.voiceState } returns guildVoiceState
        every { guildVoiceState.channel } returns null
        every { audioChannelUnion.members } returns emptyList()
        every { event.hook.interaction } returns interaction
        every { interaction.guild } returns CommandTest.guild
        every { event.reply(any<String>()) } returns replyCallbackAction
        every { replyCallbackAction.setEphemeral(true) } returns replyCallbackAction
        every { replyCallbackAction.queue(any()) } just Runs
        every { channelOption.asChannel.asAudioChannel().members } returns emptyList()

        // Act
        initiativeCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.deferReply() }
        verify(exactly = 0) { event.hook.sendMessageEmbeds(any(), *anyVararg<MessageEmbed>()) }
        verify(exactly = 0) {  webhookMessageCreateAction.setActionRow(
                initButtons.prev,
                initButtons.clear,
                initButtons.next
            ) }
        verify(exactly = 1) {
            event.reply("You must either be in a voice channel when using this command, or tag a voice channel in the channel option with people in it, or give a list of names to roll for.")
        }
    }

    @Test
    fun test_initiativeCommandWithNoNonDMMembersAndAValidChannelOption() {
        // Arrange
        val commandContext = CommandContext(event)
        val channelOptionMapping = mockk<OptionMapping>()
        val dmOptionMapping = mockk<OptionMapping>()
        val guildChannelUnion = mockk<GuildChannelUnion>()
        val audioChannel = mockk<AudioChannel>()
        val dmMember = mockk<Member>()
        val interaction = mockk<Interaction>()
        val namesMapping = mockk<OptionMapping>()


        every { channelOptionMapping.asChannel } returns guildChannelUnion
        every { namesMapping.asString } returns ""
        every { event.getOption("names") } returns namesMapping
        every { dmOptionMapping.asMember } returns dmMember
        every { guildChannelUnion.asAudioChannel() } returns audioChannel
        every { event.getOption("channel") } returns channelOptionMapping
        every { event.getOption("dm") } returns dmOptionMapping
        every { audioChannel.members } returns listOf(dmMember)
        every { event.hook.interaction } returns interaction
        every { interaction.guild } returns CommandTest.guild
        every { event.reply(any<String>()) } returns replyCallbackAction
        every { replyCallbackAction.setEphemeral(true) } returns replyCallbackAction
        every { replyCallbackAction.queue(any()) } just Runs
        every { member.user } returns user
        every { member.isOwner } returns false
        every { channelOption.asChannel.asAudioChannel().members } returns listOf(dmMember)
        every { guildVoiceState.channel } returns null


        // Act
        initiativeCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.deferReply() }
        verify(exactly = 0) { event.hook.sendMessageEmbeds(any(), *anyVararg<MessageEmbed>()) }
        verify(exactly = 0) {  webhookMessageCreateAction.setActionRow(
                initButtons.prev,
                initButtons.clear,
                initButtons.next
            ) }
        verify(exactly = 1) {
            event.reply("The amount of non DM members in the voice channel you're in, or the one you mentioned, is empty, so no rolls were done.")
        }
    }
}
