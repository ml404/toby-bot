package database.service

import database.dto.ConfigDto
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

class CasinoCooldownServiceTest {

    private val guildId = 200L
    private val discordId = 7L

    private lateinit var configService: ConfigService
    private lateinit var nowMillis: AtomicLong
    private lateinit var service: CasinoCooldownService

    @BeforeEach
    fun setup() {
        configService = mockk(relaxed = true)
        nowMillis = AtomicLong(1_000_000L)
        service = CasinoCooldownService(configService, clock = { nowMillis.get() })
    }

    private fun configReturns(value: String?) {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.CASINO_COOLDOWN_SECONDS.configValue,
                guildId.toString(),
            )
        } returns value?.let { ConfigDto(name = "x", value = it, guildId = guildId.toString()) }
    }

    @Test
    fun `tryAcquire returns Ok on the first call`() {
        configReturns(null) // default 3 s
        val result = service.tryAcquire(discordId, guildId, CasinoGameKey.COINFLIP)
        assertEquals(CasinoCooldownService.AcquireResult.Ok, result)
    }

    @Test
    fun `tryAcquire blocks until the configured cooldown elapses, then unblocks`() {
        configReturns(null) // 3 s default
        service.arm(discordId, CasinoGameKey.COINFLIP)

        // 1 ms after arm — blocked, ~3 s remaining.
        nowMillis.addAndGet(1L)
        val blocked = service.tryAcquire(discordId, guildId, CasinoGameKey.COINFLIP)
        val onCd = assertInstanceOf(CasinoCooldownService.AcquireResult.OnCooldown::class.java, blocked)
        assertTrue(onCd.remainingMs in 2_990L..3_000L, "remaining=${onCd.remainingMs}")

        // 2.999 s after arm — still blocked.
        nowMillis.addAndGet(2_998L)
        assertInstanceOf(
            CasinoCooldownService.AcquireResult.OnCooldown::class.java,
            service.tryAcquire(discordId, guildId, CasinoGameKey.COINFLIP),
        )

        // 3 s after arm — unblocked.
        nowMillis.addAndGet(2L)
        assertEquals(
            CasinoCooldownService.AcquireResult.Ok,
            service.tryAcquire(discordId, guildId, CasinoGameKey.COINFLIP),
        )
    }

    @Test
    fun `cooldown is per game and per user`() {
        configReturns(null)
        service.arm(discordId, CasinoGameKey.COINFLIP)

        // Same user, different game — not blocked.
        assertEquals(
            CasinoCooldownService.AcquireResult.Ok,
            service.tryAcquire(discordId, guildId, CasinoGameKey.SLOTS),
        )

        // Different user, same game — not blocked.
        assertEquals(
            CasinoCooldownService.AcquireResult.Ok,
            service.tryAcquire(discordId + 1, guildId, CasinoGameKey.COINFLIP),
        )
    }

    @Test
    fun `cooldown of zero seconds disables gating entirely`() {
        configReturns("0")
        service.arm(discordId, CasinoGameKey.COINFLIP)

        // Even immediately after arm, with cooldown=0 we always pass.
        assertEquals(
            CasinoCooldownService.AcquireResult.Ok,
            service.tryAcquire(discordId, guildId, CasinoGameKey.COINFLIP),
        )
    }

    @Test
    fun `cooldown clamps above the maximum`() {
        // Setting 999 seconds should clamp to MAX_COOLDOWN_SECONDS (30).
        configReturns("999")
        service.arm(discordId, CasinoGameKey.COINFLIP)
        nowMillis.addAndGet(29_000L) // 29 s in — still blocked at 30 s clamp.
        val res = service.tryAcquire(discordId, guildId, CasinoGameKey.COINFLIP)
        val onCd = assertInstanceOf(CasinoCooldownService.AcquireResult.OnCooldown::class.java, res)
        assertTrue(onCd.remainingMs in 1L..1_000L, "remaining=${onCd.remainingMs}")

        nowMillis.addAndGet(2_000L) // 31 s in — past clamp, unblocked.
        assertEquals(
            CasinoCooldownService.AcquireResult.Ok,
            service.tryAcquire(discordId, guildId, CasinoGameKey.COINFLIP),
        )
    }

    @Test
    fun `cooldown falls back to default when config value is unparseable`() {
        configReturns("not-a-number")
        service.arm(discordId, CasinoGameKey.COINFLIP)
        nowMillis.addAndGet(1L)
        // Default is 3 s — should still be blocked at 1 ms in.
        assertNotNull(
            (service.tryAcquire(discordId, guildId, CasinoGameKey.COINFLIP)
                as? CasinoCooldownService.AcquireResult.OnCooldown),
        )
    }

    @Test
    fun `reset(discordId) forgets the user cooldown without affecting others`() {
        configReturns(null)
        service.arm(discordId, CasinoGameKey.COINFLIP)
        service.arm(discordId + 1, CasinoGameKey.COINFLIP)

        service.reset(discordId)

        assertEquals(
            CasinoCooldownService.AcquireResult.Ok,
            service.tryAcquire(discordId, guildId, CasinoGameKey.COINFLIP),
        )
        assertInstanceOf(
            CasinoCooldownService.AcquireResult.OnCooldown::class.java,
            service.tryAcquire(discordId + 1, guildId, CasinoGameKey.COINFLIP),
        )
    }
}
