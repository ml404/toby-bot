package toby.command.commands.dnd

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.interactionHook
import toby.command.CommandTest.Companion.member
import toby.command.CommandTest.Companion.user
import toby.command.CommandTest.Companion.webhookMessageCreateAction
import toby.jpa.service.IUserService
import toby.jpa.service.impl.UserServiceImpl

internal class InitiativeCommandTest : CommandTest {
    lateinit var initiativeCommand: InitiativeCommand
    lateinit var userService: IUserService

    @BeforeEach
    fun setup() {
        setUpCommonMocks()
        userService = mockk<UserServiceImpl>()
        initiativeCommand = InitiativeCommand(userService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    @Test
    fun test_initiativeCommandWithCorrectSetup_WithMembers() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val channelOptionMapping = mockk<OptionMapping>()
        val dmOptionMapping = mockk<OptionMapping>()
        val guildChannelUnion = mockk<GuildChannelUnion>()
        val audioChannel = mockk<AudioChannel>()
        val dmMember = mockk<Member>()
        val interaction = mockk<Interaction>()

        every { channelOptionMapping.asChannel } returns guildChannelUnion
        every { dmOptionMapping.asMember } returns dmMember
        every { guildChannelUnion.asAudioChannel() } returns audioChannel
        every { CommandTest.event.getOption("channel") } returns channelOptionMapping
        every { CommandTest.event.getOption("dm") } returns dmOptionMapping
        every { audioChannel.members } returns listOf(member)
        every { interactionHook.interaction } returns interaction
        every { interaction.guild } returns CommandTest.guild
        every { webhookMessageCreateAction.setActionRow(any(), any(), any()) } returns webhookMessageCreateAction
        every { member.user } returns user

        // Act
        initiativeCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { CommandTest.event.deferReply() }
        verify(exactly = 1) { interactionHook.sendMessageEmbeds(any(), *anyVararg<MessageEmbed>()) }
        verify(exactly = 1) { webhookMessageCreateAction.setActionRow(any(), any(), any()) }
    }

    @Test
    fun test_initiativeCommandWithCorrectSetup_WithNames() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val namesMapping = mockk<OptionMapping>()
        val dmOptionMapping = mockk<OptionMapping>()
        val dmMember = mockk<Member>()
        val interaction = mockk<Interaction>()

        every { namesMapping.asString } returns "name1, name2, name3, name4"
        every { dmOptionMapping.asMember } returns dmMember
        every { CommandTest.event.getOption("names") } returns namesMapping
        every { CommandTest.event.getOption("dm") } returns dmOptionMapping
        every { interactionHook.interaction } returns interaction
        every { interaction.guild } returns CommandTest.guild
        every { webhookMessageCreateAction.setActionRow(any(), any(), any()) } returns webhookMessageCreateAction
        every { member.user } returns user

        // Act
        initiativeCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { CommandTest.event.deferReply() }
        verify(exactly = 1) { interactionHook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { webhookMessageCreateAction.setActionRow(any(), any(), any()) }
    }

    @Test
    fun test_initiativeCommandWithCorrectSetup_UsingMemberVoiceState() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val dmOptionMapping = mockk<OptionMapping>()
        val guildChannelUnion = mockk<GuildChannelUnion>()
        val audioChannel = mockk<AudioChannel>()
        val dmMember = mockk<Member>()
        val interaction = mockk<Interaction>()
        val guildVoiceState = mockk<GuildVoiceState>()
        val audioChannelUnion = mockk<AudioChannelUnion>()

        every { dmOptionMapping.asMember } returns dmMember
        every { guildChannelUnion.asAudioChannel() } returns audioChannel
        every { CommandTest.event.getOption("dm") } returns dmOptionMapping
        every { member.voiceState } returns guildVoiceState
        every { guildVoiceState.channel } returns audioChannelUnion
        every { audioChannelUnion.members } returns listOf(member)
        every { interactionHook.interaction } returns interaction
        every { interaction.guild } returns CommandTest.guild
        every { webhookMessageCreateAction.setActionRow(any(), any(), any()) } returns webhookMessageCreateAction
        every { member.user } returns user

        // Act
        initiativeCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { CommandTest.event.deferReply() }
        verify(exactly = 1) { interactionHook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { webhookMessageCreateAction.setActionRow(any(), any(), any()) }
    }

    @Test
    fun test_initiativeCommandWithNoValidChannel() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val dmOptionMapping = mockk<OptionMapping>()
        val audioChannel = mockk<AudioChannel>()
        val dmMember = mockk<Member>()
        val interaction = mockk<Interaction>()

        every { dmOptionMapping.asMember } returns dmMember
        every { CommandTest.event.getOption("dm") } returns dmOptionMapping
        every { audioChannel.members } returns listOf(member)
        every { interactionHook.interaction } returns interaction
        every { interaction.guild } returns CommandTest.guild
        every { CommandTest.event.reply(any<String>()) } returns CommandTest.replyCallbackAction
        every { CommandTest.replyCallbackAction.setEphemeral(true) } returns CommandTest.replyCallbackAction

        // Act
        initiativeCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { CommandTest.event.deferReply() }
        verify(exactly = 0) { interactionHook.sendMessageEmbeds(any(), *anyVararg<MessageEmbed>()) }
        verify(exactly = 0) { webhookMessageCreateAction.setActionRow(any(), any(), any()) }
        verify(exactly = 1) {
            CommandTest.event.reply("You must either be in a voice channel when using this command, or tag a voice channel in the channel option with people in it, or give a list of names to roll for.")
        }
    }

    @Test
    fun test_initiativeCommandWithNoNonDMMembersAndAValidChannelOption() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val channelOptionMapping = mockk<OptionMapping>()
        val dmOptionMapping = mockk<OptionMapping>()
        val guildChannelUnion = mockk<GuildChannelUnion>()
        val audioChannel = mockk<AudioChannel>()
        val dmMember = mockk<Member>()
        val interaction = mockk<Interaction>()

        every { channelOptionMapping.asChannel } returns guildChannelUnion
        every { dmOptionMapping.asMember } returns dmMember
        every { guildChannelUnion.asAudioChannel() } returns audioChannel
        every { CommandTest.event.getOption("channel") } returns channelOptionMapping
        every { CommandTest.event.getOption("dm") } returns dmOptionMapping
        every { audioChannel.members } returns listOf(dmMember)
        every { interactionHook.interaction } returns interaction
        every { interaction.guild } returns CommandTest.guild
        every { CommandTest.event.reply(any<String>()) } returns CommandTest.replyCallbackAction
        every { CommandTest.replyCallbackAction.setEphemeral(true) } returns CommandTest.replyCallbackAction

        // Act
        initiativeCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { CommandTest.event.deferReply() }
        verify(exactly = 0) { interactionHook.sendMessageEmbeds(any(), *anyVararg<MessageEmbed>()) }
        verify(exactly = 0) { webhookMessageCreateAction.setActionRow(any(), any(), any()) }
        verify(exactly = 1) {
            CommandTest.event.reply("The amount of non DM members in the voice channel you're in, or the one you mentioned, is empty, so no rolls were done.")
        }
    }
}
