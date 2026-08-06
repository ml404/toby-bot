package bot.toby.command.commands.music.intro

import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.commands.music.MusicCommandTest
import common.intro.IntroHealth
import core.command.CommandContext
import database.dto.guild.ConfigDto
import database.persistence.music.GuildIntroStats
import database.service.guild.ConfigService
import database.service.music.MusicFileService
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.entities.MessageEmbed
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class IntroStatsCommandTest : MusicCommandTest {

    private lateinit var musicFileService: MusicFileService
    private lateinit var configService: ConfigService
    private lateinit var command: IntroStatsCommand
    private lateinit var ctx: CommandContext

    @BeforeEach
    fun setup() {
        setupCommonMusicMocks()
        musicFileService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        command = IntroStatsCommand(musicFileService, configService)
        ctx = mockk(relaxed = true)
        every { ctx.event } returns event
        every { ctx.guild } returns guild
        every { requestingUserDto.superUser } returns true
        every { configService.getConfigByName(any(), any()) } returns null
        every { musicFileService.getGuildIntroStats(any(), any()) } returns GuildIntroStats()
        every { MusicCommandTest.playerManager.isSourceRefusingRequests() } returns false
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
        unmockkAll()
    }

    private fun runAndCaptureEmbed(): MessageEmbed {
        command.handleMusicCommand(ctx, MusicCommandTest.playerManager, requestingUserDto, 5)
        val embeds = mutableListOf<MessageEmbed>()
        verify { event.hook.sendMessageEmbeds(capture(embeds)) }
        return embeds.single()
    }

    private fun config(value: String) = ConfigDto().apply { this.value = value }

    @Test
    fun `a non-super-user is refused`() {
        // The report spans every member's intros, so it's a moderation view
        // rather than a personal one.
        every { requestingUserDto.superUser } returns false

        command.handleMusicCommand(ctx, MusicCommandTest.playerManager, requestingUserDto, 5)

        verify(exactly = 0) { musicFileService.getGuildIntroStats(any(), any()) }
    }

    @Test
    fun `broken intros are counted using the shared threshold`() {
        // Drifting from IntroSelection's definition would have the report
        // disagree with which intros actually get skipped.
        command.handleMusicCommand(ctx, MusicCommandTest.playerManager, requestingUserDto, 5)

        verify { musicFileService.getGuildIntroStats(any(), IntroHealth.UNHEALTHY_AFTER_FAILURES) }
    }

    @Test
    fun `an empty server says so rather than showing a wall of zeroes`() {
        val embed = runAndCaptureEmbed()

        assertTrue(embed.description!!.contains("Nobody"), embed.description!!)
    }

    @Test
    fun `the report totals usage, storage and faults`() {
        every { musicFileService.getGuildIntroStats(any(), any()) } returns GuildIntroStats(
            introCount = 7, userCount = 4, storedBytes = 3L * 1024 * 1024,
            totalPlays = 128, disabledCount = 1, brokenCount = 2,
        )

        val embed = runAndCaptureEmbed()
        val values = embed.fields.associate { it.name to it.value }

        assertTrue(embed.description!!.contains("7 intros across 4 members"), embed.description!!)
        assertTrue(values["Plays"]!!.contains("128"), values["Plays"]!!)
        assertTrue(values["Stored audio"]!!.contains("3.0 MB"), values["Stored audio"]!!)
        assertTrue(values["Not playing"]!!.contains("2"), values["Not playing"]!!)
    }

    @Test
    fun `a single intro and member are not described in the plural`() {
        every { musicFileService.getGuildIntroStats(any(), any()) } returns GuildIntroStats(
            introCount = 1, userCount = 1,
        )

        val embed = runAndCaptureEmbed()

        assertTrue(embed.description!!.contains("1 intro across 1 member"), embed.description!!)
    }

    @Test
    fun `a healthy server is told nothing is broken rather than left to infer it`() {
        every { musicFileService.getGuildIntroStats(any(), any()) } returns GuildIntroStats(introCount = 3)

        val embed = runAndCaptureEmbed()

        assertTrue(embed.fields.single { it.name == "Not playing" }.value!!.contains("loading fine"))
    }

    @Test
    fun `the report repeats the server settings so a silent server is explicable`() {
        // "Why is nobody's intro playing" has a config answer as often as a
        // broken-link one, and the admin asking is already looking at this.
        every { configService.getConfigByName(ConfigDto.Configurations.INTROS_ENABLED.configValue, any()) } returns
            config("false")

        val embed = runAndCaptureEmbed()

        assertTrue(embed.fields.single { it.name == "Server settings" }.value!!.contains("**off**"))
    }

    @Test
    fun `exempt channels are named where the bot can still see them`() {
        every {
            configService.getConfigByName(ConfigDto.Configurations.INTRO_EXCLUDED_CHANNELS.configValue, any())
        } returns config("500,600")
        every { guild.getVoiceChannelById(500L) } returns mockk(relaxed = true) { every { name } returns "study" }
        every { guild.getVoiceChannelById(600L) } returns null

        val embed = runAndCaptureEmbed()
        val settings = embed.fields.single { it.name == "Server settings" }.value!!

        assertTrue(settings.contains("#study"), settings)
        // A channel that has since been deleted still shows its id rather than
        // disappearing from the list.
        assertTrue(settings.contains("600"), settings)
    }

    @Test
    fun `a server with no exemptions says intros play everywhere`() {
        val embed = runAndCaptureEmbed()

        assertTrue(embed.fields.single { it.name == "Server settings" }.value!!.contains("every voice channel"))
    }

    @Test
    fun `storage is reported in units an admin can act on`() {
        assertEquals("512 B", command.formatBytes(512))
        assertEquals("2 KB", command.formatBytes(2048))
        assertEquals("1.5 MB", command.formatBytes(1024 * 1024 * 3 / 2))
        assertEquals("2.00 GB", command.formatBytes(2L * 1024 * 1024 * 1024))
    }

    // --- when the source is refusing us -------------------------------------
    //
    // The report is the one place an admin looks when several intros stop
    // playing. During a rate limit or IP block that is every intro at once,
    // and the usual reading — "these links are broken, tell their owners" —
    // is wrong in every particular.

    private fun duringOutage() {
        every { MusicCommandTest.playerManager.isSourceRefusingRequests() } returns true
    }

    @Test
    fun `an active outage is called out before anything else`() {
        duringOutage()

        val embed = runAndCaptureEmbed()

        val first = embed.fields.first()
        assertTrue(first.name!!.contains("refusing us"), first.name!!)
        assertTrue(first.value!!.contains("not a problem with anyone's intro"), first.value!!)
    }

    @Test
    fun `the outage says intro health is paused and the counts predate it`() {
        // Otherwise an admin reads a broken count that stopped moving and
        // concludes the problem is smaller than it is.
        duringOutage()

        val embed = runAndCaptureEmbed()

        val warning = embed.fields.first().value!!
        assertTrue(warning.contains("paused"), warning)
        assertTrue(warning.contains("predate"), warning)
    }

    @Test
    fun `mid-outage the broken count drops the advice to replace links`() {
        // Their links are fine, and no DM went out to explain the silence, so
        // both halves of the usual sentence are untrue.
        duringOutage()
        every { musicFileService.getGuildIntroStats(any(), any()) } returns
            GuildIntroStats(introCount = 4, userCount = 2, brokenCount = 3)

        val embed = runAndCaptureEmbed()

        val notPlaying = embed.fields.single { it.name == "Not playing" }.value!!
        assertTrue(notPlaying.contains("before the outage started"), notPlaying)
        assertTrue(!notPlaying.contains("DM'd"), notPlaying)
        assertTrue(!notPlaying.contains("/setintro"), notPlaying)
    }

    @Test
    fun `an outage is visible from the colour before a word is read`() {
        // Two runs in one capture: comparing the reports against each other
        // beats pinning the exact swatches, which are presentation detail.
        command.handleMusicCommand(ctx, MusicCommandTest.playerManager, requestingUserDto, 5)
        duringOutage()
        command.handleMusicCommand(ctx, MusicCommandTest.playerManager, requestingUserDto, 5)

        val embeds = mutableListOf<MessageEmbed>()
        verify { event.hook.sendMessageEmbeds(capture(embeds)) }
        assertEquals(2, embeds.size)
        assertNotEquals(embeds[0].colorRaw, embeds[1].colorRaw)
    }

    @Test
    fun `with the source healthy nothing about an outage is mentioned`() {
        every { musicFileService.getGuildIntroStats(any(), any()) } returns
            GuildIntroStats(introCount = 4, userCount = 2, brokenCount = 3)

        val embed = runAndCaptureEmbed()

        assertTrue(embed.fields.none { it.name!!.contains("refusing") }, embed.fields.map { it.name }.toString())
        val notPlaying = embed.fields.single { it.name == "Not playing" }.value!!
        assertTrue(notPlaying.contains("DM'd"), notPlaying)
    }

}
