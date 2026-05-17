package bot.toby.command.commands.moderation

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.botMember
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.DefaultCommandContext
import io.mockk.*
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class UnlockCommandTest : CommandTest {
    private lateinit var unlockCommand: UnlockCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        unlockCommand = UnlockCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        unmockkAll()
    }

    @Test
    fun test_UnlockRejectsNonTextChannel() {
        val ctx = DefaultCommandContext(event)
        val channelUnion = mockk<MessageChannelUnion>(relaxed = true)
        every { channelUnion.type } returns ChannelType.VOICE
        every { event.channel } returns channelUnion

        unlockCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }

    @Test
    fun test_UnlockRejectsMissingBotPermission() {
        val ctx = DefaultCommandContext(event)
        val channelUnion = mockk<MessageChannelUnion>(
            relaxed = true,
            moreInterfaces = arrayOf(TextChannel::class)
        )
        every { channelUnion.type } returns ChannelType.TEXT
        every { event.channel } returns channelUnion
        val asText = channelUnion as TextChannel
        every { member.hasPermission(asText, Permission.MANAGE_PERMISSIONS) } returns true
        every { botMember.hasPermission(asText, Permission.MANAGE_PERMISSIONS) } returns false

        unlockCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }
}
