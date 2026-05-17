package bot.toby.command.commands.moderation

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.botMember
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
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
import java.util.concurrent.TimeUnit

internal class BanCommandTest : CommandTest {
    private lateinit var banCommand: BanCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        banCommand = BanCommand(DefaultPermissionValidator())
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        unmockkAll()
    }

    @Test
    fun test_BanWithValidPermissions_bansTarget() {
        val ctx = DefaultCommandContext(event)
        banSetup(botMayBan = true, memberMayBan = true, mentionedMembers = listOf(targetMember))

        banCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { guild.ban(targetMember, 0, TimeUnit.DAYS) }
    }

    @Test
    fun test_BanWithInvalidBotPermissions_sendsError() {
        val ctx = DefaultCommandContext(event)
        banSetup(botMayBan = false, memberMayBan = true, mentionedMembers = listOf(targetMember))

        banCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }

    @Test
    fun test_BanWithInvalidUserPermissions_sendsError() {
        val ctx = DefaultCommandContext(event)
        banSetup(botMayBan = true, memberMayBan = false, mentionedMembers = listOf(targetMember))

        banCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }

    companion object {
        private fun banSetup(botMayBan: Boolean, memberMayBan: Boolean, mentionedMembers: List<Member>) {
            val auditable = mockk<AuditableRestAction<Void>>()
            val optionMapping = mockk<OptionMapping>()
            val mentions = mockk<Mentions>()

            every { event.getOption("users") } returns optionMapping
            every { event.getOption("reason") } returns null
            every { event.getOption("delete_days") } returns null
            every { optionMapping.mentions } returns mentions
            every { mentions.members } returns mentionedMembers
            every { member.canInteract(any<Member>()) } returns true
            every { member.hasPermission(Permission.BAN_MEMBERS) } returns memberMayBan
            every { botMember.hasPermission(Permission.BAN_MEMBERS) } returns botMayBan
            every { botMember.canInteract(any<Member>()) } returns botMayBan
            every { guild.ban(any<Member>(), any<Int>(), any<TimeUnit>()) } returns auditable
            every { auditable.reason(any()) } returns auditable
            every { auditable.queue(any(), any()) } just Runs
        }
    }
}
