package bot.toby.scheduling

import database.dto.ConfigDto
import database.dto.UbiDailyDto
import database.dto.UserDto
import database.service.ConfigService
import database.service.SocialCreditAwardService
import database.service.UbiDailyService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class UniversalBasicIncomeJobTest {

    private lateinit var jda: JDA
    private lateinit var userService: UserService
    private lateinit var configService: ConfigService
    private lateinit var ubiDailyService: UbiDailyService
    private lateinit var awardService: SocialCreditAwardService
    private lateinit var guild: Guild
    private lateinit var job: UniversalBasicIncomeJob

    private val guildId = 100L
    private val today: LocalDate = LocalDate.of(2026, 5, 1)
    private val clock: Clock = Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        ubiDailyService = mockk(relaxed = true)
        awardService = mockk(relaxed = true)
        guild = mockk(relaxed = true)

        val cache: SnowflakeCacheView<Guild> = mockk(relaxed = true)
        every { jda.guildCache } returns cache
        every { cache.iterator() } returns mutableListOf(guild).iterator()
        every { guild.idLong } returns guildId
        every { guild.id } returns guildId.toString()

        job = UniversalBasicIncomeJob(jda, userService, configService, ubiDailyService, awardService, clock)
    }

    private fun stubUbiAmount(value: String?) {
        val dto = value?.let { ConfigDto(name = "UBI_DAILY_AMOUNT", value = it, guildId = guildId.toString()) }
        every {
            configService.getConfigByName(ConfigDto.Configurations.UBI_DAILY_AMOUNT.configValue, guildId.toString())
        } returns dto
    }

    @Test
    fun `runDaily skips guilds with UBI unset`() {
        stubUbiAmount(null)

        job.runDaily()

        verify(exactly = 0) { awardService.award(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { ubiDailyService.upsert(any()) }
    }

    @Test
    fun `runDaily skips guilds with UBI set to 0`() {
        stubUbiAmount("0")

        job.runDaily()

        verify(exactly = 0) { awardService.award(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `runDaily grants every user the configured amount and writes ledger rows`() {
        stubUbiAmount("25")
        val alice = UserDto(discordId = 1L, guildId = guildId)
        val bob = UserDto(discordId = 2L, guildId = guildId)
        every { userService.listGuildUsers(guildId) } returns listOf(alice, bob)
        every { ubiDailyService.get(any(), guildId, today) } returns null
        every {
            awardService.award(any(), guildId, 25L, "ubi", countsAgainstDailyCap = false, at = any())
        } returns 25L

        val captured = mutableListOf<UbiDailyDto>()
        every { ubiDailyService.upsert(capture(captured)) } answers { firstArg() }

        job.runDaily()

        verify(exactly = 1) {
            awardService.award(1L, guildId, 25L, "ubi", countsAgainstDailyCap = false, at = any())
        }
        verify(exactly = 1) {
            awardService.award(2L, guildId, 25L, "ubi", countsAgainstDailyCap = false, at = any())
        }
        assertEquals(2, captured.size)
        assertEquals(setOf(1L, 2L), captured.map { it.discordId }.toSet())
        assertEquals(today, captured.first().grantDate)
        assertEquals(25L, captured.first().creditsGranted)
    }

    @Test
    fun `runDaily skips users that already have a ubi_daily row for today`() {
        stubUbiAmount("10")
        val alice = UserDto(discordId = 1L, guildId = guildId)
        val bob = UserDto(discordId = 2L, guildId = guildId)
        every { userService.listGuildUsers(guildId) } returns listOf(alice, bob)
        every { ubiDailyService.get(1L, guildId, today) } returns
            UbiDailyDto(discordId = 1L, guildId = guildId, grantDate = today, creditsGranted = 10L)
        every { ubiDailyService.get(2L, guildId, today) } returns null
        every {
            awardService.award(2L, guildId, 10L, "ubi", countsAgainstDailyCap = false, at = any())
        } returns 10L

        job.runDaily()

        verify(exactly = 0) {
            awardService.award(1L, any(), any(), any(), any(), any())
        }
        verify(exactly = 1) {
            awardService.award(2L, guildId, 10L, "ubi", countsAgainstDailyCap = false, at = any())
        }
    }

    @Test
    fun `runDaily does not write ledger row when award returns 0 (unknown user)`() {
        stubUbiAmount("15")
        val ghost = UserDto(discordId = 9L, guildId = guildId)
        every { userService.listGuildUsers(guildId) } returns listOf(ghost)
        every { ubiDailyService.get(9L, guildId, today) } returns null
        every {
            awardService.award(9L, guildId, 15L, "ubi", countsAgainstDailyCap = false, at = any())
        } returns 0L

        job.runDaily()

        verify(exactly = 0) { ubiDailyService.upsert(any()) }
    }

    @Test
    fun `runDaily uses today's UTC midnight as the award timestamp`() {
        stubUbiAmount("5")
        val alice = UserDto(discordId = 1L, guildId = guildId)
        every { userService.listGuildUsers(guildId) } returns listOf(alice)
        every { ubiDailyService.get(1L, guildId, today) } returns null
        val instantSlot = slot<Instant>()
        every {
            awardService.award(1L, guildId, 5L, "ubi", countsAgainstDailyCap = false, at = capture(instantSlot))
        } returns 5L

        job.runDaily()

        assertEquals(today.atStartOfDay().toInstant(ZoneOffset.UTC), instantSlot.captured)
    }
}
