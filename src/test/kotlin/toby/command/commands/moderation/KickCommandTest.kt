package toby.command.commands.moderation

import io.mockk.*
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import net.dv8tion.jda.api.interactions.commands.OptionMapping
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

internal class KickCommandTest : CommandTest {
    private lateinit var kickCommand: KickCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        kickCommand = KickCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        unmockkAll()
    }

    @Test
    fun test_KickWithValidPermissions_kicksEveryoneInChannel() {
        // Arrange
        val commandContext = CommandContext(event)
        kickSetup(botKickOthers = true, memberKickOthers = true, mentionedMembers = listOf(targetMember))

        // Act
        kickCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.guild }
        verify(exactly = 1) { guild.kick(targetMember) }
    }

    @Test
    fun test_KickWithValidPermissionsAndMultipleMembers_kicksEveryoneInChannel() {
        // Arrange
        val commandContext = CommandContext(event)
        kickSetup(
            botKickOthers = true,
            memberKickOthers = true,
            mentionedMembers = listOf(member, targetMember)
        )

        // Act
        kickCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.guild }
        verify(exactly = 1) { guild.kick(member) }
        verify(exactly = 1) { guild.kick(targetMember) }
    }

    @Test
    fun test_KickWithInvalidBotPermissions_throwsError() {
        // Arrange
        val commandContext = CommandContext(event)
        kickSetup(botKickOthers = false, memberKickOthers = true, mentionedMembers = listOf(targetMember))

        // Act
        kickCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.guild }
        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }

    @Test
    fun test_KickWithInvalidUserPermissions_throwsError() {
        // Arrange
        val commandContext = CommandContext(event)
        kickSetup(botKickOthers = true, memberKickOthers = false, mentionedMembers = listOf(targetMember))

        // Act
        kickCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.guild }
        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }

    companion object {
        private fun kickSetup(botKickOthers: Boolean, memberKickOthers: Boolean, mentionedMembers: List<Member>) {
            val audioChannelUnion = mockk<AudioChannelUnion>()
            val guildVoiceState = mockk<GuildVoiceState>()
            val auditableRestAction = mockk<AuditableRestAction<Void>>()
            val optionMapping = mockk<OptionMapping>()
            val mentions = mockk<Mentions>()

            every { event.getOption("users") } returns optionMapping
            every { optionMapping.mentions } returns mentions
            every { mentions.members } returns mentionedMembers
            every { member.canInteract(any<Member>()) } returns true
            every { botMember.hasPermission(Permission.KICK_MEMBERS) } returns botKickOthers
            every { botMember.canInteract(any<Member>()) } returns botKickOthers
            every { member.voiceState } returns guildVoiceState
            every { member.hasPermission(Permission.KICK_MEMBERS) } returns memberKickOthers
            every { guildVoiceState.channel } returns audioChannelUnion
            every { guild.kick(any<Member>()) } returns auditableRestAction
            every { auditableRestAction.reason("because you told me to.") } returns auditableRestAction
            every { auditableRestAction.queue(any(), any()) } just Runs
        }
    }
}
