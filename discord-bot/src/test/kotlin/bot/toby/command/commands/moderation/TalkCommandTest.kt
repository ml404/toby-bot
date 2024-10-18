package bot.toby.command.commands.moderation

import bot.toby.command.CommandContextImpl
import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.botMember
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.CommandTest.Companion.targetMember
import io.mockk.*
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

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
        val commandContext = CommandContextImpl(event)
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
        val commandContext = CommandContextImpl(event)
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
        val commandContext = CommandContextImpl(event)
        talkSetup(false, true, listOf(targetMember))

        // Act
        talkCommand.handle(commandContext, requestingUserDto, 0)
        val effectiveName = targetMember.effectiveName

        // Assert
        verify(exactly = 1) { event.guild }
        verify(exactly = 1) {
            event.hook.sendMessage("I'm not allowed to unmute $effectiveName"
            )
        }
    }

    @Test
    fun test_talkWithInvalidUserPermissions_throwsError() {
        // Arrange
        val commandContext = CommandContextImpl(event)
        talkSetup(true, false, listOf(targetMember))

        // Act
        talkCommand.handle(commandContext, requestingUserDto, 0)
        val effectiveName = targetMember.effectiveName

        // Assert
        verify(exactly = 1) { event.guild }
        verify(exactly = 1) {
            event.hook.sendMessage("You aren't allowed to unmute $effectiveName"
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
