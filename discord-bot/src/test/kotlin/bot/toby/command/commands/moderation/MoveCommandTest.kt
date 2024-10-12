package bot.toby.command.commands.moderation

import bot.database.dto.ConfigDto
import bot.database.service.IConfigService
import bot.toby.command.CommandContext
import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.botMember
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.targetMember
import io.mockk.*
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class MoveCommandTest : CommandTest {
    private lateinit var moveCommand: MoveCommand
    private lateinit var configService: IConfigService

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        configService = mockk()
        every { configService.getConfigByName(ConfigDto.Configurations.MOVE.configValue, "1") } returns
                ConfigDto(ConfigDto.Configurations.MOVE.configValue, "CHANNEL", "1")
        moveCommand = MoveCommand(configService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    @Test
    fun test_moveWithValidPermissions_movesEveryoneInChannel() {
        // Arrange
        val commandContext = CommandContext(event)
        moveSetup(botMoveOthers = true, memberMoveOthers = true, mentionedMembers = listOf(targetMember))

        // Act
        moveCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.guild }
        verify(exactly = 1) { guild.moveVoiceMember(eq(targetMember), any<AudioChannel>()) }
    }

    @Test
    fun test_moveWithValidPermissionsAndMultipleMembers_movesEveryoneInChannel() {
        // Arrange
        val commandContext = CommandContext(event)
        moveSetup(
            botMoveOthers = true,
            memberMoveOthers = true,
            mentionedMembers = listOf(member, targetMember)
        )

        // Act
        moveCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.guild }
        verify(exactly = 1) { guild.moveVoiceMember(eq(member), any<AudioChannel>()) }
        verify(exactly = 1) { guild.moveVoiceMember(eq(targetMember), any<AudioChannel>()) }
    }

    @Test
    fun test_moveWithInvalidBotPermissions_throwsError() {
        // Arrange
        val commandContext = CommandContext(event)
        moveSetup(botMoveOthers = false, memberMoveOthers = true, mentionedMembers = listOf(targetMember))
        val guildVoiceState = mockk<GuildVoiceState>()
        every { targetMember.voiceState } returns guildVoiceState
        every { guildVoiceState.inAudioChannel() } returns true

        // Act
        moveCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.guild }
        verify(exactly = 1) {
            event.hook.sendMessage(
                any<String>()
            )
        }
    }

    @Test
    fun test_moveWithInvalidUserPermissions_throwsError() {
        // Arrange
        val commandContext = CommandContext(event)
        moveSetup(botMoveOthers = true, memberMoveOthers = false, mentionedMembers = listOf(targetMember))

        // Act
        moveCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.guild }
        verify(exactly = 1) {
            event.hook.sendMessage(
                any<String>()
            )
        }
    }

    @Test
    fun test_moveWithUserNotInChannel_throwsError() {
        // Arrange
        val commandContext = CommandContext(event)
        moveSetup(botMoveOthers = true, memberMoveOthers = true, mentionedMembers = listOf(targetMember))
        val guildVoiceState = mockk<GuildVoiceState>()
        every { targetMember.voiceState } returns guildVoiceState
        every { guildVoiceState.inAudioChannel() } returns false

        // Act
        moveCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.guild }
        verify(exactly = 1) {
            event.hook.sendMessage(
                any<String>()
            )
        }
    }

    companion object {
        private fun moveSetup(botMoveOthers: Boolean, memberMoveOthers: Boolean, mentionedMembers: List<Member>) {
            val audioChannelUnion = mockk<AudioChannelUnion>()
            val guildVoiceState = mockk<GuildVoiceState>()
            val auditableRestAction = mockk<AuditableRestAction<Void>>()
            val channelOptionMapping = mockk<OptionMapping>()
            val userOptionMapping = mockk<OptionMapping>()
            val mentions = mockk<Mentions>()
            val guildChannelUnion = mockk<GuildChannelUnion>()
            val voiceChannel = mockk<VoiceChannel>()

            every { targetMember.voiceState } returns guildVoiceState
            every { guildVoiceState.inAudioChannel() } returns true
            every { event.getOption("channel") } returns channelOptionMapping
            every { event.getOption("users") } returns userOptionMapping
            every { userOptionMapping.mentions } returns mentions
            every { channelOptionMapping.asChannel } returns guildChannelUnion
            every { guildChannelUnion.name } returns "Channel"
            every { guildChannelUnion.asVoiceChannel() } returns voiceChannel
            every { mentions.members } returns mentionedMembers
            every { member.canInteract(any<Member>()) } returns true
            every { botMember.hasPermission(Permission.VOICE_MOVE_OTHERS) } returns botMoveOthers
            every { botMember.canInteract(any<Member>()) } returns botMoveOthers
            every { member.voiceState } returns guildVoiceState
            every { member.hasPermission(Permission.VOICE_MOVE_OTHERS) } returns memberMoveOthers
            every { guildVoiceState.channel } returns audioChannelUnion
            every { guild.getVoiceChannelsByName("CHANNEL", true) } returns listOf(voiceChannel)
            every { guild.moveVoiceMember(any(), any<AudioChannel>()) } returns auditableRestAction
            every { auditableRestAction.reason(any()) } returns auditableRestAction
            every { auditableRestAction.queue(any(), any()) } just Runs
        }
    }
}
