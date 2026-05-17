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
import net.dv8tion.jda.api.managers.channel.concrete.TextChannelManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class SlowmodeCommandTest : CommandTest {
    private lateinit var slowmodeCommand: SlowmodeCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        slowmodeCommand = SlowmodeCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        unmockkAll()
    }

    @Test
    fun test_SlowmodeRejectsNonTextChannel() {
        val ctx = DefaultCommandContext(event)
        val channelUnion = mockk<MessageChannelUnion>(relaxed = true)
        every { channelUnion.type } returns ChannelType.VOICE
        every { event.channel } returns channelUnion

        slowmodeCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }

    @Test
    fun test_SlowmodeAppliesSeconds() {
        val ctx = DefaultCommandContext(event)
        val channelUnion = mockk<MessageChannelUnion>(
            relaxed = true,
            moreInterfaces = arrayOf(TextChannel::class)
        )
        every { channelUnion.type } returns ChannelType.TEXT
        every { event.channel } returns channelUnion
        val asText = channelUnion as TextChannel
        every { member.hasPermission(asText, Permission.MANAGE_CHANNEL) } returns true
        every { botMember.hasPermission(asText, Permission.MANAGE_CHANNEL) } returns true
        val secondsOpt = mockk<OptionMapping>()
        every { event.getOption("seconds") } returns secondsOpt
        every { secondsOpt.asLong } returns 30L
        val manager = mockk<TextChannelManager>(relaxed = true)
        every { asText.manager } returns manager
        every { manager.setSlowmode(30) } returns manager
        every { manager.reason(any()) } returns manager
        every { manager.queue(any(), any()) } just Runs

        slowmodeCommand.handle(ctx, requestingUserDto, 0)

        verify(exactly = 1) { manager.setSlowmode(30) }
    }
}
