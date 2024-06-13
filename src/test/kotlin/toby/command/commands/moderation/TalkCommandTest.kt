package toby.command.commands.moderation

import io.mockk.*
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.botMember
import toby.command.CommandTest.Companion.event
import toby.command.CommandTest.Companion.guild
import toby.command.CommandTest.Companion.member
import toby.command.CommandTest.Companion.requestingUserDto
import toby.command.CommandTest.Companion.targetMember

internal class TalkCommandTest : CommandTest {
    lateinit var talkCommand: TalkCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        talkCommand = TalkCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    @Test
    fun test_talkWithValidPermissions_unmutesEveryoneInChannel() {
        // Arrange
        val commandContext = CommandContext(event)
        talkSetup(true, true, listOf(targetMember))

        // Act
        talkCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.guild }
        verify(exactly = 1) { guild.mute(targetMember, false) }
    }

    @Test
    fun test_talkWithValidPermissionsAndMultipleMembers_unmutesEveryoneInChannel() {
        // Arrange
        val commandContext = CommandContext(event)
        talkSetup(true, true, listOf(member, targetMember))

        // Act
        talkCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.guild }
        verify(exactly = 1) { guild.mute(member, false) }
        verify(exactly = 1) { guild.mute(targetMember, false) }
    }

    @Test
    fun test_talkWithInvalidBotPermissions_throwsError() {
        // Arrange
        val commandContext = CommandContext(event)
        talkSetup(false, true, listOf(targetMember))

        // Act
        talkCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.guild }
        verify(exactly = 1) {
            event.hook.sendMessageFormat(
                eq("I'm not allowed to unmute %s"),
                eq(targetMember)
            )
        }
    }

    @Test
    fun test_talkWithInvalidUserPermissions_throwsError() {
        // Arrange
        val commandContext = CommandContext(event)
        talkSetup(true, false, listOf(targetMember))

        // Act
        talkCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.guild }
        verify(exactly = 1) {
            event.hook.sendMessageFormat(
                eq("You aren't allowed to unmute %s"),
                eq(targetMember)
            )
        }
    }

    companion object {
        private fun talkSetup(voiceMuteOtherBot: Boolean, voiceMuteOtherMember: Boolean, targetMember: List<Member>) {
            val audioChannelUnion = mockk<AudioChannelUnion>()
            val guildVoiceState = mockk<GuildVoiceState>()
            val auditableRestAction = mockk<AuditableRestAction<Void>>()

            every { member.canInteract(any<Member>()) } returns true
            every { botMember.hasPermission(Permission.VOICE_MUTE_OTHERS) } returns voiceMuteOtherBot
            every { member.voiceState } returns guildVoiceState
            every { member.hasPermission(Permission.VOICE_MUTE_OTHERS) } returns voiceMuteOtherMember
            every { guildVoiceState.channel } returns audioChannelUnion
            every { audioChannelUnion.members } returns targetMember
            every { guild.mute(any(), eq(false)) } returns auditableRestAction
            every { auditableRestAction.reason("Unmuted") } returns auditableRestAction
            every { auditableRestAction.queue() } just Runs
        }
    }
}
