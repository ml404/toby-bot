package bot.toby.command.commands.misc

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.utils.FileUpload
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import web.profile.ProfileCardData
import web.profile.ProfileCardRenderer
import web.service.ProfileCardAggregator
import java.time.Instant

internal class ProfileCommandTest : CommandTest {

    private lateinit var aggregator: ProfileCardAggregator
    private lateinit var renderer: ProfileCardRenderer
    private lateinit var command: ProfileCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        aggregator = mockk(relaxed = true)
        renderer = mockk(relaxed = true)
        command = ProfileCommand(aggregator, renderer)
        every { member.idLong } returns 1L
        every { guild.idLong } returns 100L
        every { guild.id } returns "100"
        every { member.effectiveName } returns "Alice"
        // The happy path goes through the chained `event.hook.sendMessageEmbeds(embed).addFiles(file).queue()`;
        // CommandTest's relaxed mocks cover most of that — only addFiles needs an explicit return wire.
        every { webhookMessageCreateAction.addFiles(any<FileUpload>()) } returns webhookMessageCreateAction
        every { webhookMessageCreateAction.queue() } just runs
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        unmockkAll()
    }

    @Test
    fun `happy path defers and posts an embed with an attached PNG`() {
        every { event.getOption("user") } returns null
        every { aggregator.build(guild, member) } returns sampleData()
        every { renderer.renderPng(any()) } returns ByteArray(2048)

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) { event.deferReply() }
        verify(exactly = 1) { aggregator.build(guild, member) }
        verify(exactly = 1) { renderer.renderPng(any()) }
        verify(exactly = 1) { event.hook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { webhookMessageCreateAction.addFiles(any<FileUpload>()) }
    }

    @Test
    fun `targets the user option when provided`() {
        val targetMember = mockk<Member>(relaxed = true).also {
            every { it.idLong } returns 7L
            every { it.effectiveName } returns "Bob"
        }
        val opt = mockk<OptionMapping>(relaxed = true).also { every { it.asMember } returns targetMember }
        every { event.getOption("user") } returns opt
        every { aggregator.build(guild, targetMember) } returns sampleData()
        every { renderer.renderPng(any()) } returns ByteArray(2048)

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) { aggregator.build(guild, targetMember) }
    }

    @Test
    fun `replies with a render-error message when the renderer throws`() {
        every { event.getOption("user") } returns null
        every { aggregator.build(guild, member) } returns sampleData()
        every { renderer.renderPng(any()) } throws RuntimeException("boom")
        val reply = captureReply()

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        assertTrue(reply.isCaptured, "expected a sendMessage call on the render-error branch")
        assertTrue(
            reply.captured.contains("couldn't render"),
            "reply text should explain the render failure, got: ${reply.captured}",
        )
    }

    @Test
    fun `option list carries only the user option`() {
        // Pin the option shape so a future refactor that accidentally drops
        // the user option (or adds a required one) fails CI loudly.
        val opts = command.optionData
        assertTrue(opts.size == 1, "expected one option, got ${opts.size}")
        assertTrue(opts.first().name == "user", "expected option named 'user', got ${opts.first().name}")
        assertTrue(!opts.first().isRequired, "user option must be optional — defaults to invoker")
    }

    /**
     * Routes both `replyEphemeralAndDelete` and `replyAndDelete` paths
     * through `event.hook.sendMessage(...)`, capturing the message text
     * for assertion. Mirrors the same pattern in `LotteryCommandTest`.
     */
    private fun captureReply(): CapturingSlot<String> {
        val slot = slot<String>()
        every { event.hook.sendMessage(capture(slot)) } returns webhookMessageCreateAction
        return slot
    }

    private fun sampleData() = ProfileCardData(
        avatarUrl = "https://avatar/1.png",
        displayName = "Alice",
        guildName = "Test Guild",
        level = 5,
        xpIntoLevel = 100,
        xpForNextLevel = 250,
        totalXp = 500,
        socialCredit = 1_000,
        equippedTitle = null,
        recentAchievements = listOf(
            ProfileCardData.AchievementSnapshot("🎲", "First Roll", Instant.now()),
        ),
    )
}
