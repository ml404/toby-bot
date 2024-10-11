package bot.toby.command.commands.moderation

import bot.toby.command.CommandContext
import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
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

internal class ShhCommandTest : CommandTest {
    private lateinit var shhCommand: ShhCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        shhCommand = ShhCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    @Test
    fun test_shhWithValidPermissions_mutesEveryoneInChannel() {
        // Arrange
        val commandContext = CommandContext(event)
        shhSetup(
            voiceMuteOtherBot = true,
            voiceMuteOtherMember = true,
            targetMembers = listOf(targetMember)
        )

        // Act
        shhCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.guild }
        verify(exactly = 1) { guild.mute(targetMember, true) }
    }

    @Test
    fun test_shhWithValidPermissionsAndMultipleMembers_mutesEveryoneInChannel() {
        // Arrange
        val commandContext = CommandContext(event)
        shhSetup(
            voiceMuteOtherBot = true,
            voiceMuteOtherMember = true,
            targetMembers = listOf(CommandTest.member, targetMember)
        )

        // Act
        shhCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.guild }
        verify(exactly = 1) { guild.mute(CommandTest.member, true) }
        verify(exactly = 1) { guild.mute(targetMember, true) }
    }

    @Test
    fun test_shhWithInvalidBotPermissions_throwsError() {
        // Arrange
        val commandContext = CommandContext(event)
        shhSetup(
            voiceMuteOtherBot = false,
            voiceMuteOtherMember = true,
            targetMembers = listOf(targetMember)
        )

        // Act
        shhCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        val effectiveName = targetMember.effectiveName
        // Assert
        verify(exactly = 1) { event.guild }
        verify(exactly = 1) {
            event.hook.sendMessage("I'm not allowed to mute $effectiveName")
        }
    }

    @Test
    fun test_shhWithInvalidUserPermissions_throwsError() {
        // Arrange
        val commandContext = CommandContext(event)
        shhSetup(
            voiceMuteOtherBot = true,
            voiceMuteOtherMember = false,
            targetMembers = listOf(targetMember)
        )
        val effectiveName = targetMember.effectiveName

        // Act
        shhCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.guild }
        verify(exactly = 1) {
            event.hook.sendMessage(
                eq("You aren't allowed to mute $effectiveName"),
            )
        }
    }

    companion object {
        private fun shhSetup(voiceMuteOtherBot: Boolean, voiceMuteOtherMember: Boolean, targetMembers: List<Member>) {
            val audioChannelUnion = mockk<AudioChannelUnion>()
            val guildVoiceState = mockk<GuildVoiceState>()
            val auditableRestAction = mockk<AuditableRestAction<Void>>()

            every { CommandTest.member.canInteract(any<Member>()) } returns true
            every { CommandTest.botMember.hasPermission(Permission.VOICE_MUTE_OTHERS) } returns voiceMuteOtherBot
            every { CommandTest.member.voiceState } returns guildVoiceState
            every { CommandTest.member.hasPermission(Permission.VOICE_MUTE_OTHERS) } returns voiceMuteOtherMember
            every { guildVoiceState.channel } returns audioChannelUnion
            every { audioChannelUnion.members } returns targetMembers
            every { guild.mute(any(), eq(true)) } returns auditableRestAction
            every { auditableRestAction.reason("Muted") } returns auditableRestAction
            every { auditableRestAction.reason("Muted").queue() } just Runs
            every { auditableRestAction.queue(any()) } just Runs
        }
    }
}
