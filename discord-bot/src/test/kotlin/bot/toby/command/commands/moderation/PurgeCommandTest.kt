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
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class PurgeCommandTest : CommandTest {
    private lateinit var purgeCommand: PurgeCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        purgeCommand = PurgeCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        unmockkAll()
    }

    @Test
    fun test_PurgeRejectsNonTextChannel() {
        val ctx = DefaultCommandContext(event)
        val channelUnion = mockk<MessageChannelUnion>(relaxed = true)
        every { channelUnion.type } returns ChannelType.VOICE
        every { event.channel } returns channelUnion

        purgeCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }

    @Test
    fun test_PurgeRejectsMissingMemberPermission() {
        val ctx = DefaultCommandContext(event)
        val channelUnion = mockk<MessageChannelUnion>(
            relaxed = true,
            moreInterfaces = arrayOf(TextChannel::class)
        )
        every { channelUnion.type } returns ChannelType.TEXT
        every { event.channel } returns channelUnion
        val asText = channelUnion as TextChannel
        every { member.hasPermission(asText, Permission.MESSAGE_MANAGE) } returns false
        every { botMember.hasPermission(asText, Permission.MESSAGE_MANAGE) } returns true

        purgeCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }

    @Test
    fun test_PurgeRejectsCountOutOfRange() {
        val ctx = DefaultCommandContext(event)
        val channelUnion = mockk<MessageChannelUnion>(
            relaxed = true,
            moreInterfaces = arrayOf(TextChannel::class)
        )
        every { channelUnion.type } returns ChannelType.TEXT
        every { event.channel } returns channelUnion
        val asText = channelUnion as TextChannel
        every { member.hasPermission(asText, Permission.MESSAGE_MANAGE) } returns true
        every { botMember.hasPermission(asText, Permission.MESSAGE_MANAGE) } returns true
        val countOpt = mockk<OptionMapping>()
        every { event.getOption("count") } returns countOpt
        every { countOpt.asLong } returns 500L
        every { event.getOption("user") } returns null

        purgeCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }
}
