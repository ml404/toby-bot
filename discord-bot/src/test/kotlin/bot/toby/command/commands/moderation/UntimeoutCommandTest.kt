package bot.toby.command.commands.moderation

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.botMember
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.CommandTest.Companion.targetMember
import bot.toby.command.DefaultCommandContext
import bot.toby.command.DefaultPermissionValidator
import io.mockk.*
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class UntimeoutCommandTest : CommandTest {
    private lateinit var untimeoutCommand: UntimeoutCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        untimeoutCommand = UntimeoutCommand(DefaultPermissionValidator())
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        unmockkAll()
    }

    @Test
    fun test_UntimeoutWithValidPermissions_removesTimeout() {
        val ctx = DefaultCommandContext(event)
        untimeoutSetup(botMay = true, memberMay = true, mentionedMembers = listOf(targetMember))

        untimeoutCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { targetMember.removeTimeout() }
    }

    @Test
    fun test_UntimeoutWithInvalidUserPermissions_sendsError() {
        val ctx = DefaultCommandContext(event)
        untimeoutSetup(botMay = true, memberMay = false, mentionedMembers = listOf(targetMember))

        untimeoutCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }

    companion object {
        private fun untimeoutSetup(botMay: Boolean, memberMay: Boolean, mentionedMembers: List<Member>) {
            val auditable = mockk<AuditableRestAction<Void>>()
            val usersOpt = mockk<OptionMapping>()
            val mentions = mockk<Mentions>()

            every { event.getOption("users") } returns usersOpt
            every { usersOpt.mentions } returns mentions
            every { mentions.members } returns mentionedMembers
            every { member.canInteract(any<Member>()) } returns true
            every { member.hasPermission(Permission.MODERATE_MEMBERS) } returns memberMay
            every { botMember.hasPermission(Permission.MODERATE_MEMBERS) } returns botMay
            every { botMember.canInteract(any<Member>()) } returns botMay
            every { targetMember.removeTimeout() } returns auditable
            every { auditable.reason(any()) } returns auditable
            every { auditable.queue(any(), any()) } just Runs
        }
    }
}
