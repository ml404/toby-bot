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
import java.time.Duration

internal class TimeoutCommandTest : CommandTest {
    private lateinit var timeoutCommand: TimeoutCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        timeoutCommand = TimeoutCommand(DefaultPermissionValidator())
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        unmockkAll()
    }

    @Test
    fun test_TimeoutWithValidPermissions_timesOutTarget() {
        val ctx = DefaultCommandContext(event)
        timeoutSetup(botMay = true, memberMay = true, minutes = 5L, mentionedMembers = listOf(targetMember))

        timeoutCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { targetMember.timeoutFor(Duration.ofMinutes(5L)) }
    }

    @Test
    fun test_TimeoutWithInvalidBotPermissions_sendsError() {
        val ctx = DefaultCommandContext(event)
        timeoutSetup(botMay = false, memberMay = true, minutes = 5L, mentionedMembers = listOf(targetMember))

        timeoutCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }

    @Test
    fun test_TimeoutWithZeroMinutes_sendsError() {
        val ctx = DefaultCommandContext(event)
        timeoutSetup(botMay = true, memberMay = true, minutes = 0L, mentionedMembers = listOf(targetMember))

        timeoutCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }

    companion object {
        private fun timeoutSetup(
            botMay: Boolean, memberMay: Boolean, minutes: Long, mentionedMembers: List<Member>
        ) {
            val auditable = mockk<AuditableRestAction<Void>>()
            val usersOpt = mockk<OptionMapping>()
            val minutesOpt = mockk<OptionMapping>()
            val mentions = mockk<Mentions>()

            every { event.getOption("users") } returns usersOpt
            every { event.getOption("minutes") } returns minutesOpt
            every { event.getOption("reason") } returns null
            every { usersOpt.mentions } returns mentions
            every { mentions.members } returns mentionedMembers
            every { minutesOpt.asLong } returns minutes
            every { member.canInteract(any<Member>()) } returns true
            every { member.hasPermission(Permission.MODERATE_MEMBERS) } returns memberMay
            every { botMember.hasPermission(Permission.MODERATE_MEMBERS) } returns botMay
            every { botMember.canInteract(any<Member>()) } returns botMay
            every { targetMember.timeoutFor(any<Duration>()) } returns auditable
            every { auditable.reason(any()) } returns auditable
            every { auditable.queue(any(), any()) } just Runs
        }
    }
}
