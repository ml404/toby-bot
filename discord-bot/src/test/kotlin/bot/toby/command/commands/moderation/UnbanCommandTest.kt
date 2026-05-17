package bot.toby.command.commands.moderation

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.botMember
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.DefaultCommandContext
import io.mockk.*
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class UnbanCommandTest : CommandTest {
    private lateinit var unbanCommand: UnbanCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        unbanCommand = UnbanCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        unmockkAll()
    }

    @Test
    fun test_UnbanWithValidPermissions_callsUnban() {
        val ctx = DefaultCommandContext(event)
        val auditable = mockk<AuditableRestAction<Void>>()
        val opt = mockk<OptionMapping>()
        every { event.getOption("user_id") } returns opt
        every { opt.asString } returns "123456789"
        every { member.hasPermission(Permission.BAN_MEMBERS) } returns true
        every { botMember.hasPermission(Permission.BAN_MEMBERS) } returns true
        every { guild.unban(any<UserSnowflake>()) } returns auditable
        every { auditable.reason(any()) } returns auditable
        every { auditable.queue(any(), any()) } just Runs

        unbanCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { guild.unban(any<UserSnowflake>()) }
    }

    @Test
    fun test_UnbanWithInvalidId_sendsError() {
        val ctx = DefaultCommandContext(event)
        val opt = mockk<OptionMapping>()
        every { event.getOption("user_id") } returns opt
        every { opt.asString } returns "not-a-number"
        every { member.hasPermission(Permission.BAN_MEMBERS) } returns true
        every { botMember.hasPermission(Permission.BAN_MEMBERS) } returns true

        unbanCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }

    @Test
    fun test_UnbanWithoutBanPermission_sendsError() {
        val ctx = DefaultCommandContext(event)
        every { member.hasPermission(Permission.BAN_MEMBERS) } returns false

        unbanCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }
}
