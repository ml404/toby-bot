package bot.toby.command.commands.misc

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.interactionHook
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import database.dto.AchievementDto
import database.dto.UserDto
import database.service.AchievementService
import database.service.AchievementService.AchievementView
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.MessageEmbed
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class AchievementsCommandTest : CommandTest {

    private lateinit var achievementService: AchievementService
    private lateinit var command: AchievementsCommand
    private lateinit var userDto: UserDto

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        achievementService = mockk(relaxed = true)
        userDto = mockk(relaxed = true)
        command = AchievementsCommand(achievementService)
        every { event.getOption("user") } returns null
        every { event.deferReply(true) } returns CommandTest.replyCallbackAction
        every { member.effectiveName } returns "Tester"
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearMocks(event, interactionHook)
    }

    private fun captureEmbed(): MessageEmbed {
        val slot = slot<MessageEmbed>()
        verify(exactly = 1) { interactionHook.sendMessageEmbeds(capture(slot)) }
        return slot.captured
    }

    private fun runCommand() {
        val ctx = DefaultCommandContext(event)
        command.handle(ctx, userDto, 0)
    }

    // ---------- empty / error paths ----------

    @Test
    fun `empty state shows seeded-soon description and no fields`() {
        every { achievementService.listFor(any(), any()) } returns emptyList()

        runCommand()

        val embed = captureEmbed()
        assertTrue(embed.description!!.contains("No achievements have been seeded yet"))
        assertTrue(embed.fields.isEmpty())
    }

    @Test
    fun `non-guild context returns the server-only error string`() {
        every { event.guild } returns null

        runCommand()

        verify(exactly = 1) {
            interactionHook.sendMessage("This command can only be used in a server.")
        }
        verify(exactly = 0) { interactionHook.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `defers reply as ephemeral`() {
        every { achievementService.listFor(any(), any()) } returns emptyList()
        runCommand()
        verify(exactly = 1) { event.deferReply(true) }
    }

    // ---------- header / summary ----------

    @Test
    fun `header shows X of Y unlocked and total XP earned`() {
        val unlocked = listOf(
            mkView(code = "a", category = "casino", xpReward = 25, unlockedAt = Instant.now()),
            mkView(code = "b", category = "casino", xpReward = 50, unlockedAt = Instant.now()),
            mkView(code = "c", category = "level", xpReward = 100, unlockedAt = Instant.now()),
        )
        val locked = (1..5).map { mkView(code = "x$it", category = "level", xpReward = 10) }
        every { achievementService.listFor(any(), any()) } returns unlocked + locked

        runCommand()
        val embed = captureEmbed()

        assertTrue(embed.description!!.contains("**3** / **8** unlocked"), embed.description)
        assertTrue(embed.description!!.contains("**175 XP**"), embed.description)
    }

    // ---------- grouping ----------

    @Test
    fun `achievements are grouped by category into separate fields in declared order`() {
        val views = listOf(
            mkView(code = "v", category = "voice", name = "VoiceA"),
            mkView(code = "m", category = "music", name = "MusicA"),
            mkView(code = "so", category = "social", name = "SocialA"),
            mkView(code = "ca", category = "casino", name = "CasinoA"),
            mkView(code = "l", category = "level", name = "LevelA"),
            mkView(code = "s", category = "streak", name = "StreakA"),
        )
        every { achievementService.listFor(any(), any()) } returns views

        runCommand()
        val embed = captureEmbed()

        val titles = embed.fields.map { it.name!! }
        assertEquals(6, titles.size)
        assertTrue(titles[0].contains("Streaks"), titles[0])
        assertTrue(titles[1].contains("Levels"), titles[1])
        assertTrue(titles[2].contains("Casino"), titles[2])
        assertTrue(titles[3].contains("Social"), titles[3])
        assertTrue(titles[4].contains("Music"), titles[4])
        assertTrue(titles[5].contains("Voice"), titles[5])
        titles.forEach { assertTrue(it.contains("(0/1)"), it) }
    }

    @Test
    fun `unknown category falls back to capitalised name`() {
        every { achievementService.listFor(any(), any()) } returns listOf(
            mkView(code = "x", category = "experimental", name = "Exp")
        )

        runCommand()
        val embed = captureEmbed()

        val title = embed.fields.single().name!!
        assertTrue(title.contains("Experimental"), title)
    }

    // ---------- line rendering ----------

    @Test
    fun `unlocked entry renders checkmark icon name description timestamp and rewards`() {
        val at = Instant.parse("2024-01-01T00:00:00Z")
        every { achievementService.listFor(any(), any()) } returns listOf(
            mkView(
                code = "tip_giver", category = "social", name = "Generous",
                description = "Tip another user for the first time.", icon = "🎁",
                xpReward = 25, creditReward = 50, threshold = 1, unlockedAt = at
            )
        )

        runCommand()
        val body = captureEmbed().fields.single().value!!

        assertTrue(body.contains("✅"), body)
        assertTrue(body.contains("🎁"), body)
        assertTrue(body.contains("**Generous**"), body)
        assertTrue(body.contains("Tip another user for the first time."), body)
        assertTrue(body.contains("<t:${at.epochSecond}:R>"), body)
        assertTrue(body.contains("+25 XP"), body)
        assertTrue(body.contains("+50¢"), body)
    }

    @Test
    fun `locked counter achievement shows progress bar with filled and empty blocks`() {
        every { achievementService.listFor(any(), any()) } returns listOf(
            mkView(
                code = "p", category = "voice", name = "Counter",
                description = "d", icon = "🎙️",
                xpReward = 0, creditReward = 0, threshold = 10, progress = 4
            )
        )

        runCommand()
        val body = captureEmbed().fields.single().value!!

        assertTrue(body.contains("🔒"), body)
        assertTrue(body.contains("[████░░░░░░]"), body)
        assertTrue(body.contains("4/10"), body)
    }

    @Test
    fun `locked one-shot achievement (threshold 1) shows no progress bar`() {
        every { achievementService.listFor(any(), any()) } returns listOf(
            mkView(
                code = "o", category = "casino", name = "OneShot",
                description = "d", threshold = 1, progress = 0
            )
        )

        runCommand()
        val body = captureEmbed().fields.single().value!!

        assertTrue(body.contains("🔒"), body)
        assertFalse(body.contains("█"), body)
        assertFalse(body.contains("░"), body)
        assertFalse(body.contains("1/1"), body)
    }

    @Test
    fun `level_5 with threshold 5 and progress 3 renders accurate progress — regression for the wiring bug`() {
        every { achievementService.listFor(any(), any()) } returns listOf(
            mkView(
                code = "level_5", category = "level", name = "Getting Started",
                description = "Reach level 5.", icon = "🥉",
                xpReward = 25, threshold = 5, progress = 3
            )
        )

        runCommand()
        val body = captureEmbed().fields.single().value!!

        // 3 of 5 ratio at 10-wide bar = 6 filled blocks.
        assertTrue(body.contains("[██████░░░░]"), body)
        assertTrue(body.contains("3/5"), body)
    }

    // ---------- sorting within a category ----------

    @Test
    fun `entries are sorted within a category — unlocked first newest-first then locked highest-progress-first`() {
        val older = Instant.parse("2024-01-01T00:00:00Z")
        val newer = Instant.parse("2024-06-01T00:00:00Z")
        every { achievementService.listFor(any(), any()) } returns listOf(
            mkView(code = "lo_lo", category = "casino", name = "LockedLow", threshold = 10, progress = 1),
            mkView(code = "un_old", category = "casino", name = "UnlockedOld", unlockedAt = older),
            mkView(code = "lo_hi", category = "casino", name = "LockedHigh", threshold = 10, progress = 8),
            mkView(code = "un_new", category = "casino", name = "UnlockedNew", unlockedAt = newer),
        )

        runCommand()
        val body = captureEmbed().fields.single().value!!

        val iNew = body.indexOf("UnlockedNew")
        val iOld = body.indexOf("UnlockedOld")
        val iHi = body.indexOf("LockedHigh")
        val iLo = body.indexOf("LockedLow")
        assertTrue(iNew in 0 until iOld, "expected UnlockedNew before UnlockedOld in: $body")
        assertTrue(iOld in 0 until iHi, "expected UnlockedOld before LockedHigh in: $body")
        assertTrue(iHi in 0 until iLo, "expected LockedHigh before LockedLow in: $body")
    }

    // ---------- field truncation ----------

    @Test
    fun `field body trims with and-N-more when it would exceed the JDA 1024-char limit`() {
        val many = (1..50).map { idx ->
            mkView(
                code = "big$idx",
                category = "casino",
                name = "Name$idx",
                description = "x".repeat(80),
                threshold = 1,
            )
        }
        every { achievementService.listFor(any(), any()) } returns many

        runCommand()
        val body = captureEmbed().fields.single().value!!

        assertTrue(body.length <= 1024, "field body length ${body.length} exceeds 1024")
        assertTrue(body.contains("…and") && body.endsWith("more"), "expected truncation marker, got: ${body.takeLast(60)}")
    }

    private fun mkView(
        code: String,
        category: String,
        name: String = "Name",
        description: String = "desc",
        icon: String? = "🏅",
        xpReward: Int = 0,
        creditReward: Long = 0L,
        threshold: Long = 1L,
        progress: Long = 0L,
        unlockedAt: Instant? = null,
    ): AchievementView = AchievementView(
        achievement = AchievementDto(
            id = code.hashCode().toLong(),
            code = code,
            name = name,
            description = description,
            category = category,
            icon = icon,
            xpReward = xpReward,
            creditReward = creditReward,
            threshold = threshold,
        ),
        unlockedAt = unlockedAt,
        progress = progress,
    )
}
