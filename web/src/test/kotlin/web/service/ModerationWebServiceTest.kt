package web.service

import database.dto.ConfigDto
import database.dto.MonthlyCreditSnapshotDto
import database.dto.TitleDto
import database.dto.UserDto
import database.service.ConfigService
import database.service.MonthlyCreditSnapshotService
import database.service.TitleService
import database.service.UbiDailyService
import database.service.UserService
import database.service.VoiceSessionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.SelfMember
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ModerationWebServiceTest {

    private lateinit var jda: JDA
    private lateinit var userService: UserService
    private lateinit var configService: ConfigService
    private lateinit var introWebService: IntroWebService
    private lateinit var voiceSessionService: VoiceSessionService
    private lateinit var titleService: TitleService
    private lateinit var snapshotService: MonthlyCreditSnapshotService
    private lateinit var ubiDailyService: UbiDailyService
    private lateinit var jackpotService: database.service.JackpotService
    private lateinit var casinoAdminService: database.service.CasinoAdminService
    private lateinit var jackpotLotteryService: database.service.JackpotLotteryService
    private lateinit var levelRoleRewardService: database.service.LevelRoleRewardService
    private lateinit var service: ModerationWebService

    private lateinit var guild: Guild

    private val guildId = 222L
    private val ownerId = 100L
    private val superUserId = 101L
    private val plainUserId = 102L
    private val targetUserId = 103L

    private lateinit var eventPublisher: org.springframework.context.ApplicationEventPublisher

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        introWebService = mockk(relaxed = true)
        voiceSessionService = mockk(relaxed = true)
        titleService = mockk(relaxed = true)
        snapshotService = mockk(relaxed = true)
        ubiDailyService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        casinoAdminService = mockk(relaxed = true)
        jackpotLotteryService = mockk(relaxed = true)
        levelRoleRewardService = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        guild = mockk(relaxed = true)
        service = ModerationWebService(
            jda,
            userService,
            configService,
            introWebService,
            voiceSessionService,
            titleService,
            snapshotService,
            ubiDailyService,
            jackpotService,
            casinoAdminService,
            jackpotLotteryService,
            levelRoleRewardService,
            eventPublisher
        )

        every { jda.getGuildById(guildId) } returns guild
        every { guild.id } returns guildId.toString()
        every { guild.name } returns "Test Guild"
        // Default upsert: report a "created" outcome so callers can branch
        // on the result without each test having to wire it up.
        every { configService.upsertConfig(any(), any(), any()) } answers {
            ConfigService.UpsertResult.Created(ConfigDto(firstArg(), secondArg(), thirdArg()))
        }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun mockMember(id: Long, isOwner: Boolean = false): Member {
        val m = mockk<Member>(relaxed = true)
        every { m.idLong } returns id
        every { m.id } returns id.toString()
        every { m.isOwner } returns isOwner
        every { guild.getMemberById(id) } returns m
        return m
    }

    // ---- canModerate ----

    @Test
    fun `canModerate true for owner`() {
        mockMember(ownerId, isOwner = true)
        every { introWebService.isSuperUser(ownerId, guildId) } returns false
        assertTrue(service.canModerate(ownerId, guildId))
    }

    @Test
    fun `canModerate true for superuser`() {
        mockMember(superUserId)
        every { introWebService.isSuperUser(superUserId, guildId) } returns true
        assertTrue(service.canModerate(superUserId, guildId))
    }

    @Test
    fun `canModerate false for plain user`() {
        mockMember(plainUserId)
        every { introWebService.isSuperUser(plainUserId, guildId) } returns false
        assertFalse(service.canModerate(plainUserId, guildId))
    }

    @Test
    fun `canModerate false when bot is not in guild`() {
        every { jda.getGuildById(guildId) } returns null
        assertFalse(service.canModerate(ownerId, guildId))
    }

    // ---- togglePermission ----

    @Test
    fun `togglePermission denied for non-moderator`() {
        mockMember(plainUserId)
        every { introWebService.isSuperUser(plainUserId, guildId) } returns false
        val err = service.togglePermission(plainUserId, guildId, targetUserId, UserDto.Permissions.MUSIC)
        assertEquals("You are not allowed to moderate this server.", err)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `togglePermission denies self-change`() {
        mockMember(superUserId)
        every { introWebService.isSuperUser(superUserId, guildId) } returns true
        val err = service.togglePermission(superUserId, guildId, superUserId, UserDto.Permissions.MUSIC)
        assertEquals("You cannot change your own permissions.", err)
    }

    @Test
    fun `togglePermission SUPERUSER denied for non-owner`() {
        mockMember(superUserId)
        mockMember(targetUserId)
        every { introWebService.isSuperUser(superUserId, guildId) } returns true
        val err = service.togglePermission(superUserId, guildId, targetUserId, UserDto.Permissions.SUPERUSER)
        assertEquals("Only the server owner can toggle SUPERUSER.", err)
    }

    @Test
    fun `togglePermission flips MUSIC and persists`() {
        mockMember(ownerId, isOwner = true)
        mockMember(targetUserId)
        val target = UserDto(discordId = targetUserId, guildId = guildId).apply { musicPermission = true }
        every { userService.getUserById(targetUserId, guildId) } returns target
        every { userService.getUserById(ownerId, guildId) } returns UserDto(discordId = ownerId, guildId = guildId)

        val err = service.togglePermission(ownerId, guildId, targetUserId, UserDto.Permissions.MUSIC)
        assertNull(err)
        assertFalse(target.musicPermission)
        verify(exactly = 1) { userService.updateUser(target) }
    }

    @Test
    fun `togglePermission superuser cannot adjust another superuser`() {
        mockMember(superUserId)
        mockMember(targetUserId)
        every { introWebService.isSuperUser(superUserId, guildId) } returns true
        val actor = UserDto(discordId = superUserId, guildId = guildId).apply { superUser = true }
        val target = UserDto(discordId = targetUserId, guildId = guildId).apply { superUser = true }
        every { userService.getUserById(superUserId, guildId) } returns actor
        every { userService.getUserById(targetUserId, guildId) } returns target

        val err = service.togglePermission(superUserId, guildId, targetUserId, UserDto.Permissions.MUSIC)
        assertNotNull(err)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `togglePermission creates missing UserDto before flipping`() {
        mockMember(ownerId, isOwner = true)
        mockMember(targetUserId)
        every { userService.getUserById(targetUserId, guildId) } returns null
        every { userService.getUserById(ownerId, guildId) } returns UserDto(discordId = ownerId, guildId = guildId)

        val err = service.togglePermission(ownerId, guildId, targetUserId, UserDto.Permissions.DIG)
        assertNull(err)
        verify { userService.createNewUser(any()) }
        verify { userService.updateUser(any()) }
    }

    // ---- adjustSocialCredit ----

    @Test
    fun `adjustSocialCredit rejects non-owner`() {
        mockMember(superUserId)
        every { introWebService.isSuperUser(superUserId, guildId) } returns true
        val err = service.adjustSocialCredit(superUserId, guildId, targetUserId, 10)
        assertEquals("Only the server owner can adjust social credit.", err)
    }

    @Test
    fun `adjustSocialCredit increments and persists`() {
        mockMember(ownerId, isOwner = true)
        val target = UserDto(discordId = targetUserId, guildId = guildId).apply { socialCredit = 5L }
        every { userService.getUserById(targetUserId, guildId) } returns target

        val err = service.adjustSocialCredit(ownerId, guildId, targetUserId, 3)
        assertNull(err)
        assertEquals(8L, target.socialCredit)
    }

    // ---- updateConfig ----

    @Test
    fun `updateConfig rejects non-owner`() {
        mockMember(superUserId)
        every { introWebService.isSuperUser(superUserId, guildId) } returns true
        val err = service.updateConfig(superUserId, guildId, ConfigDto.Configurations.VOLUME, "50")
        assertEquals("Only the server owner can change guild config.", err)
    }

    @Test
    fun `updateConfig rejects non-integer volume`() {
        mockMember(ownerId, isOwner = true)
        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.VOLUME, "not-a-number")
        assertEquals("Value must be a whole number.", err)
    }

    @Test
    fun `updateConfig writes the value when no row exists`() {
        mockMember(ownerId, isOwner = true)

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.VOLUME, "75")
        assertNull(err)
        verify { configService.upsertConfig("DEFAULT_VOLUME", "75", guildId.toString()) }
    }

    @Test
    fun `updateConfig rejects MOVE when channel name does not exist`() {
        mockMember(ownerId, isOwner = true)
        every { guild.getVoiceChannelsByName("nope", true) } returns emptyList()

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.MOVE, "nope")
        assertNotNull(err)
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
    }

    @Test
    fun `updateConfig accepts POKER_RAKE_PCT in 0 to 20 inclusive`() {
        mockMember(ownerId, isOwner = true)
        val key = ConfigDto.Configurations.POKER_RAKE_PCT

        assertNull(service.updateConfig(ownerId, guildId, key, "0"))
        assertNull(service.updateConfig(ownerId, guildId, key, "5"))
        assertNull(service.updateConfig(ownerId, guildId, key, "20"))
        verify { configService.upsertConfig(key.configValue, "0", guildId.toString()) }
        verify { configService.upsertConfig(key.configValue, "20", guildId.toString()) }

        assertNotNull(service.updateConfig(ownerId, guildId, key, "-1"))
        assertNotNull(service.updateConfig(ownerId, guildId, key, "21"))
        assertNotNull(service.updateConfig(ownerId, guildId, key, "wibble"))
    }

    @Test
    fun `updateConfig accepts POKER chip-amount keys when value is a positive long`() {
        mockMember(ownerId, isOwner = true)
        val keys = listOf(
            ConfigDto.Configurations.POKER_SMALL_BLIND,
            ConfigDto.Configurations.POKER_BIG_BLIND,
            ConfigDto.Configurations.POKER_SMALL_BET,
            ConfigDto.Configurations.POKER_BIG_BET,
            ConfigDto.Configurations.POKER_MIN_BUY_IN,
            ConfigDto.Configurations.POKER_MAX_BUY_IN,
        )
        for (key in keys) {
            val err = service.updateConfig(ownerId, guildId, key, "250")
            assertNull(err, "expected $key to accept 250")
            verify { configService.upsertConfig(key.configValue, "250", guildId.toString()) }
        }
    }

    @Test
    fun `updateConfig rejects POKER chip-amount keys when value is non-numeric or below 1`() {
        mockMember(ownerId, isOwner = true)
        val key = ConfigDto.Configurations.POKER_SMALL_BLIND

        val nonNumeric = service.updateConfig(ownerId, guildId, key, "abc")
        assertNotNull(nonNumeric)
        val zero = service.updateConfig(ownerId, guildId, key, "0")
        assertNotNull(zero)
        val negative = service.updateConfig(ownerId, guildId, key, "-5")
        assertNotNull(negative)
        verify(exactly = 0) { configService.upsertConfig(key.configValue, any(), any()) }
    }

    @Test
    fun `updateConfig accepts POKER_MAX_SEATS only within 2 to 9`() {
        mockMember(ownerId, isOwner = true)
        val key = ConfigDto.Configurations.POKER_MAX_SEATS

        assertNull(service.updateConfig(ownerId, guildId, key, "2"))
        assertNull(service.updateConfig(ownerId, guildId, key, "9"))
        verify { configService.upsertConfig(key.configValue, "2", guildId.toString()) }
        verify { configService.upsertConfig(key.configValue, "9", guildId.toString()) }

        assertNotNull(service.updateConfig(ownerId, guildId, key, "1"))
        assertNotNull(service.updateConfig(ownerId, guildId, key, "10"))
        assertNotNull(service.updateConfig(ownerId, guildId, key, "n/a"))
    }

    @Test
    fun `updateConfig accepts POKER_SHOT_CLOCK_SECONDS in 0 to 600 (0 disables)`() {
        mockMember(ownerId, isOwner = true)
        val key = ConfigDto.Configurations.POKER_SHOT_CLOCK_SECONDS

        assertNull(service.updateConfig(ownerId, guildId, key, "0"))
        assertNull(service.updateConfig(ownerId, guildId, key, "30"))
        assertNull(service.updateConfig(ownerId, guildId, key, "600"))
        verify { configService.upsertConfig(key.configValue, "0", guildId.toString()) }
        verify { configService.upsertConfig(key.configValue, "30", guildId.toString()) }

        assertNotNull(service.updateConfig(ownerId, guildId, key, "-1"))
        assertNotNull(service.updateConfig(ownerId, guildId, key, "601"))
        assertNotNull(service.updateConfig(ownerId, guildId, key, "asdf"))
    }

    @Test
    fun `updateConfig accepts BLACKJACK_RAKE_PCT in 0 to 20 inclusive`() {
        mockMember(ownerId, isOwner = true)
        val key = ConfigDto.Configurations.BLACKJACK_RAKE_PCT

        assertNull(service.updateConfig(ownerId, guildId, key, "0"))
        assertNull(service.updateConfig(ownerId, guildId, key, "5"))
        assertNull(service.updateConfig(ownerId, guildId, key, "20"))
        verify { configService.upsertConfig(key.configValue, "0", guildId.toString()) }
        verify { configService.upsertConfig(key.configValue, "20", guildId.toString()) }

        assertNotNull(service.updateConfig(ownerId, guildId, key, "-1"))
        assertNotNull(service.updateConfig(ownerId, guildId, key, "21"))
        assertNotNull(service.updateConfig(ownerId, guildId, key, "abc"))
    }

    @Test
    fun `updateConfig accepts BLACKJACK_MIN_ANTE when value is a positive long and rejects 0`() {
        // Min-ante is a floor — 0 has no meaning, so it stays rejected
        // (unlike MAX_ANTE which now accepts 0 as "unlimited").
        mockMember(ownerId, isOwner = true)
        val key = ConfigDto.Configurations.BLACKJACK_MIN_ANTE

        assertNull(service.updateConfig(ownerId, guildId, key, "10"))
        assertNull(service.updateConfig(ownerId, guildId, key, "500"))
        verify { configService.upsertConfig(key.configValue, "10", guildId.toString()) }
        verify { configService.upsertConfig(key.configValue, "500", guildId.toString()) }

        assertNotNull(service.updateConfig(ownerId, guildId, key, "0"))
        assertNotNull(service.updateConfig(ownerId, guildId, key, "-5"))
        assertNotNull(service.updateConfig(ownerId, guildId, key, "wibble"))
    }

    @Test
    fun `updateConfig accepts BLACKJACK_MAX_ANTE positive value or 0 (unlimited) and rejects negatives`() {
        // 0 is the "no upper cap" sentinel — admins type 0 instead of a
        // giant number. cfgLongMax expands stored 0 to Long.MAX_VALUE at
        // read time. Negatives + non-numeric values still fail validation.
        mockMember(ownerId, isOwner = true)
        val key = ConfigDto.Configurations.BLACKJACK_MAX_ANTE

        assertNull(service.updateConfig(ownerId, guildId, key, "500"))
        assertNull(service.updateConfig(ownerId, guildId, key, "0"), "0 is the unlimited sentinel")
        verify { configService.upsertConfig(key.configValue, "500", guildId.toString()) }
        verify { configService.upsertConfig(key.configValue, "0", guildId.toString()) }

        assertNotNull(service.updateConfig(ownerId, guildId, key, "-5"))
        assertNotNull(service.updateConfig(ownerId, guildId, key, "wibble"))
    }

    @Test
    fun `updateConfig accepts every per-game MAX_STAKE key with 0 as unlimited`() {
        // Pin the unlimited semantic for every minigame max-cap key in
        // one place — drift in the validation arm (e.g. someone splitting
        // a key back into the min-cap arm) trips here immediately.
        mockMember(ownerId, isOwner = true)
        val maxKeys = listOf(
            ConfigDto.Configurations.DICE_MAX_STAKE,
            ConfigDto.Configurations.COINFLIP_MAX_STAKE,
            ConfigDto.Configurations.SLOTS_MAX_STAKE,
            ConfigDto.Configurations.HIGHLOW_MAX_STAKE,
            ConfigDto.Configurations.BACCARAT_MAX_STAKE,
            ConfigDto.Configurations.KENO_MAX_STAKE,
            ConfigDto.Configurations.SCRATCH_MAX_STAKE,
            ConfigDto.Configurations.HOLDEM_MAX_STAKE,
            ConfigDto.Configurations.DUEL_MAX_STAKE,
            ConfigDto.Configurations.POKER_MAX_BUY_IN,
        )
        for (key in maxKeys) {
            assertNull(service.updateConfig(ownerId, guildId, key, "0"), "$key should accept 0 (unlimited)")
            assertNull(service.updateConfig(ownerId, guildId, key, "1000"), "$key should accept 1000")
            assertNotNull(service.updateConfig(ownerId, guildId, key, "-1"), "$key should reject negatives")
        }
    }

    @Test
    fun `updateConfig keeps JACKPOT_STAKE_ANCHOR as a min-only field that rejects 0`() {
        // The anchor is a divisor in JackpotHelper.rollOnWin (stake / anchor),
        // so 0 isn't "unlimited" — it's pathological. Stays rejected even
        // after the broader 0=unlimited semantic for max-stake fields.
        mockMember(ownerId, isOwner = true)
        val key = ConfigDto.Configurations.JACKPOT_STAKE_ANCHOR

        assertNull(service.updateConfig(ownerId, guildId, key, "500"))
        assertNotNull(service.updateConfig(ownerId, guildId, key, "0"), "anchor must reject 0")
        assertNotNull(service.updateConfig(ownerId, guildId, key, "-1"))
    }

    @Test
    fun `updateConfig accepts BLACKJACK_MAX_SEATS only within 2 to 7`() {
        mockMember(ownerId, isOwner = true)
        val key = ConfigDto.Configurations.BLACKJACK_MAX_SEATS

        assertNull(service.updateConfig(ownerId, guildId, key, "2"))
        assertNull(service.updateConfig(ownerId, guildId, key, "7"))
        verify { configService.upsertConfig(key.configValue, "2", guildId.toString()) }
        verify { configService.upsertConfig(key.configValue, "7", guildId.toString()) }

        assertNotNull(service.updateConfig(ownerId, guildId, key, "1"))
        assertNotNull(service.updateConfig(ownerId, guildId, key, "8"))
        assertNotNull(service.updateConfig(ownerId, guildId, key, "n/a"))
    }

    @Test
    fun `updateConfig accepts BLACKJACK_SHOT_CLOCK_SECONDS in 0 to 600 (0 disables)`() {
        mockMember(ownerId, isOwner = true)
        val key = ConfigDto.Configurations.BLACKJACK_SHOT_CLOCK_SECONDS

        assertNull(service.updateConfig(ownerId, guildId, key, "0"))
        assertNull(service.updateConfig(ownerId, guildId, key, "30"))
        assertNull(service.updateConfig(ownerId, guildId, key, "600"))
        verify { configService.upsertConfig(key.configValue, "0", guildId.toString()) }
        verify { configService.upsertConfig(key.configValue, "30", guildId.toString()) }

        assertNotNull(service.updateConfig(ownerId, guildId, key, "-1"))
        assertNotNull(service.updateConfig(ownerId, guildId, key, "601"))
        assertNotNull(service.updateConfig(ownerId, guildId, key, "asdf"))
    }

    @Test
    fun `updateConfig accepts BLACKJACK_DEALER_HITS_SOFT_17 only as a boolean string`() {
        mockMember(ownerId, isOwner = true)
        val key = ConfigDto.Configurations.BLACKJACK_DEALER_HITS_SOFT_17

        assertNull(service.updateConfig(ownerId, guildId, key, "true"))
        assertNull(service.updateConfig(ownerId, guildId, key, "false"))
        // Validator lower-cases the input.
        assertNull(service.updateConfig(ownerId, guildId, key, "TRUE"))
        verify { configService.upsertConfig(key.configValue, "true", guildId.toString()) }
        verify { configService.upsertConfig(key.configValue, "false", guildId.toString()) }

        assertNotNull(service.updateConfig(ownerId, guildId, key, "yes"))
        assertNotNull(service.updateConfig(ownerId, guildId, key, "1"))
        assertNotNull(service.updateConfig(ownerId, guildId, key, ""))
    }

    @Test
    fun `updateConfig accepts BLACKJACK_BJ_PAYOUT_NUM and DEN in 1 to 10`() {
        mockMember(ownerId, isOwner = true)
        val keys = listOf(
            ConfigDto.Configurations.BLACKJACK_BJ_PAYOUT_NUM,
            ConfigDto.Configurations.BLACKJACK_BJ_PAYOUT_DEN,
        )
        for (key in keys) {
            assertNull(service.updateConfig(ownerId, guildId, key, "1"))
            assertNull(service.updateConfig(ownerId, guildId, key, "3"))
            assertNull(service.updateConfig(ownerId, guildId, key, "10"))
            verify { configService.upsertConfig(key.configValue, "1", guildId.toString()) }
            verify { configService.upsertConfig(key.configValue, "10", guildId.toString()) }

            assertNotNull(service.updateConfig(ownerId, guildId, key, "0"))
            assertNotNull(service.updateConfig(ownerId, guildId, key, "11"))
            assertNotNull(service.updateConfig(ownerId, guildId, key, "abc"))
        }
    }

    @Test
    fun `updateConfig accepts MOVE when channel name exists`() {
        mockMember(ownerId, isOwner = true)
        val vc = mockk<VoiceChannel>(relaxed = true)
        every { guild.getVoiceChannelsByName("afk", true) } returns listOf(vc)

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.MOVE, "afk")
        assertNull(err)
        verify { configService.upsertConfig("DEFAULT_MOVE_CHANNEL", "afk", guildId.toString()) }
    }

    // ---- kickMember ----

    @Test
    fun `kickMember denied for non-moderator`() {
        mockMember(plainUserId)
        mockMember(targetUserId)
        every { introWebService.isSuperUser(plainUserId, guildId) } returns false
        val err = service.kickMember(plainUserId, guildId, targetUserId, null)
        assertEquals("You are not allowed to moderate this server.", err)
    }

    @Test
    fun `kickMember denied when actor lacks KICK_MEMBERS`() {
        val actor = mockMember(ownerId, isOwner = true)
        val target = mockMember(targetUserId)
        val bot = mockk<SelfMember>(relaxed = true)
        every { guild.selfMember } returns bot
        every { actor.canInteract(target) } returns true
        every { actor.hasPermission(Permission.KICK_MEMBERS) } returns false

        val err = service.kickMember(ownerId, guildId, targetUserId, null)
        assertNotNull(err)
    }

    // ---- moveMembers ----

    @Test
    fun `moveMembers error when destination not found`() {
        mockMember(ownerId, isOwner = true)
        every { guild.getVoiceChannelById(999L) } returns null

        val result = service.moveMembers(ownerId, guildId, 999L, listOf(targetUserId))
        assertEquals("Target voice channel not found.", result.error)
    }

    @Test
    fun `moveMembers error when member list empty`() {
        mockMember(ownerId, isOwner = true)
        val result = service.moveMembers(ownerId, guildId, 999L, emptyList())
        assertEquals("Select at least one member to move.", result.error)
    }

    // ---- banMember / unbanUser / timeoutMember / untimeoutMember ----

    @Test
    fun `banMember denied for non-moderator`() {
        mockMember(plainUserId)
        every { introWebService.isSuperUser(plainUserId, guildId) } returns false
        val err = service.banMember(plainUserId, guildId, targetUserId, null, 0)
        assertEquals("You are not allowed to moderate this server.", err)
    }

    @Test
    fun `banMember denied when actor lacks BAN_MEMBERS`() {
        val actor = mockMember(ownerId, isOwner = true)
        val target = mockMember(targetUserId)
        val bot = mockk<SelfMember>(relaxed = true)
        every { guild.selfMember } returns bot
        every { actor.canInteract(target) } returns true
        every { actor.hasPermission(Permission.BAN_MEMBERS) } returns false

        val err = service.banMember(ownerId, guildId, targetUserId, null, 0)
        assertNotNull(err)
    }

    @Test
    fun `banMember error when target not in guild`() {
        mockMember(ownerId, isOwner = true)
        every { guild.getMemberById(targetUserId) } returns null
        val err = service.banMember(ownerId, guildId, targetUserId, null, 0)
        assertEquals("Target is not a member of that server.", err)
    }

    @Test
    fun `unbanUser denied for non-moderator`() {
        mockMember(plainUserId)
        every { introWebService.isSuperUser(plainUserId, guildId) } returns false
        val err = service.unbanUser(plainUserId, guildId, targetUserId)
        assertEquals("You are not allowed to moderate this server.", err)
    }

    @Test
    fun `unbanUser denied when actor lacks BAN_MEMBERS`() {
        val actor = mockMember(ownerId, isOwner = true)
        val bot = mockk<SelfMember>(relaxed = true)
        every { guild.selfMember } returns bot
        every { actor.hasPermission(Permission.BAN_MEMBERS) } returns false

        val err = service.unbanUser(ownerId, guildId, targetUserId)
        assertEquals("You need the Ban Members permission.", err)
    }

    @Test
    fun `timeoutMember denied for non-moderator`() {
        mockMember(plainUserId)
        every { introWebService.isSuperUser(plainUserId, guildId) } returns false
        val err = service.timeoutMember(plainUserId, guildId, targetUserId, 5L, null)
        assertEquals("You are not allowed to moderate this server.", err)
    }

    @Test
    fun `timeoutMember rejects out-of-range duration`() {
        mockMember(ownerId, isOwner = true)
        mockMember(targetUserId)
        val err = service.timeoutMember(ownerId, guildId, targetUserId, 0L, null)
        assertNotNull(err)
        assertTrue(err!!.contains("1"))
    }

    @Test
    fun `timeoutMember rejects duration above 28 days`() {
        mockMember(ownerId, isOwner = true)
        mockMember(targetUserId)
        val tooLong = 28L * 24L * 60L + 1L
        val err = service.timeoutMember(ownerId, guildId, targetUserId, tooLong, null)
        assertNotNull(err)
    }

    @Test
    fun `timeoutMember denied when actor lacks MODERATE_MEMBERS`() {
        val actor = mockMember(ownerId, isOwner = true)
        val target = mockMember(targetUserId)
        val bot = mockk<SelfMember>(relaxed = true)
        every { guild.selfMember } returns bot
        every { actor.canInteract(target) } returns true
        every { actor.hasPermission(Permission.MODERATE_MEMBERS) } returns false

        val err = service.timeoutMember(ownerId, guildId, targetUserId, 5L, null)
        assertNotNull(err)
    }

    @Test
    fun `untimeoutMember denied for non-moderator`() {
        mockMember(plainUserId)
        every { introWebService.isSuperUser(plainUserId, guildId) } returns false
        val err = service.untimeoutMember(plainUserId, guildId, targetUserId)
        assertEquals("You are not allowed to moderate this server.", err)
    }

    // ---- purgeMessages / lockChannel / setSlowmode ----

    @Test
    fun `purgeMessages denied for non-moderator`() {
        mockMember(plainUserId)
        every { introWebService.isSuperUser(plainUserId, guildId) } returns false
        val result = service.purgeMessages(plainUserId, guildId, 333L, 10, null)
        assertEquals("You are not allowed to moderate this server.", result.error)
    }

    @Test
    fun `purgeMessages rejects count out of range`() {
        mockMember(ownerId, isOwner = true)
        val result = service.purgeMessages(ownerId, guildId, 333L, 500, null)
        assertEquals("Count must be between 1 and 100.", result.error)
    }

    @Test
    fun `purgeMessages rejects count below 1`() {
        mockMember(ownerId, isOwner = true)
        val result = service.purgeMessages(ownerId, guildId, 333L, 0, null)
        assertEquals("Count must be between 1 and 100.", result.error)
    }

    @Test
    fun `purgeMessages error when channel not found`() {
        mockMember(ownerId, isOwner = true)
        every { guild.getTextChannelById(333L) } returns null
        val result = service.purgeMessages(ownerId, guildId, 333L, 10, null)
        assertEquals("Text channel not found.", result.error)
    }

    @Test
    fun `lockChannel denied for non-moderator`() {
        mockMember(plainUserId)
        every { introWebService.isSuperUser(plainUserId, guildId) } returns false
        val err = service.lockChannel(plainUserId, guildId, 333L, true)
        assertEquals("You are not allowed to moderate this server.", err)
    }

    @Test
    fun `setSlowmode denied for non-moderator`() {
        mockMember(plainUserId)
        every { introWebService.isSuperUser(plainUserId, guildId) } returns false
        val err = service.setSlowmode(plainUserId, guildId, 333L, 5)
        assertEquals("You are not allowed to moderate this server.", err)
    }

    @Test
    fun `setSlowmode rejects out-of-range seconds`() {
        mockMember(ownerId, isOwner = true)
        every { guild.getTextChannelById(333L) } returns mockk(relaxed = true)
        val err = service.setSlowmode(ownerId, guildId, 333L, 99999)
        assertEquals("Seconds must be between 0 and 21600.", err)
    }

    @Test
    fun `setSlowmode rejects negative seconds`() {
        mockMember(ownerId, isOwner = true)
        every { guild.getTextChannelById(333L) } returns mockk(relaxed = true)
        val err = service.setSlowmode(ownerId, guildId, 333L, -1)
        assertEquals("Seconds must be between 0 and 21600.", err)
    }

    @Test
    fun `lockChannel error when channel not found`() {
        mockMember(ownerId, isOwner = true)
        every { guild.getTextChannelById(333L) } returns null
        val err = service.lockChannel(ownerId, guildId, 333L, true)
        assertEquals("Text channel not found.", err)
    }

    // ---- createPoll ----

    @Test
    fun `createPoll rejects empty options`() {
        mockMember(ownerId, isOwner = true)
        val err = service.createPoll(ownerId, guildId, 333L, "Yes?", emptyList())
        assertEquals("Provide at least 1 option.", err)
    }

    @Test
    fun `createPoll rejects more than 4 options`() {
        mockMember(ownerId, isOwner = true)
        val options = (1..5).map { "opt$it" }
        val err = service.createPoll(ownerId, guildId, 333L, "Q?", options)
        assertNotNull(err)
        assertTrue(err!!.contains("4"))
    }

    @Test
    fun `createPoll rejects empty question`() {
        mockMember(ownerId, isOwner = true)
        val err = service.createPoll(ownerId, guildId, 333L, "   ", listOf("a", "b"))
        assertEquals("Question is required.", err)
    }

    @Test
    fun `createPoll denied for non-moderator`() {
        mockMember(plainUserId)
        every { introWebService.isSuperUser(plainUserId, guildId) } returns false
        val err = service.createPoll(plainUserId, guildId, 333L, "Q?", listOf("a", "b"))
        assertEquals("You are not allowed to moderate this server.", err)
    }

    @Test
    fun `createPoll error when text channel not found`() {
        mockMember(ownerId, isOwner = true)
        every { guild.getTextChannelById(333L) } returns null

        val err = service.createPoll(ownerId, guildId, 333L, "Q?", listOf("a", "b"))
        assertEquals("Text channel not found.", err)
    }

    @Test
    fun `createPoll error when bot lacks channel permission`() {
        mockMember(ownerId, isOwner = true)
        val channel = mockk<TextChannel>(relaxed = true)
        every { guild.getTextChannelById(333L) } returns channel
        val bot = mockk<SelfMember>(relaxed = true)
        every { guild.selfMember } returns bot
        every { bot.hasPermission(channel, Permission.MESSAGE_SEND, Permission.MESSAGE_ADD_REACTION) } returns false

        val err = service.createPoll(ownerId, guildId, 333L, "Q?", listOf("a", "b"))
        assertEquals("Bot cannot post in that channel.", err)
    }

    // ---- getModeratableGuilds ----

    @Test
    fun `getModeratableGuilds filters to guilds where actor can moderate`() {
        val mutual = listOf(
            GuildInfo(id = guildId.toString(), name = "A", iconHash = null),
            GuildInfo(id = "999", name = "B", iconHash = null)
        )
        every { introWebService.getMutualGuilds("token") } returns mutual
        mockMember(ownerId, isOwner = true)
        every { jda.getGuildById(999L) } returns null

        val result = service.getModeratableGuilds("token", ownerId)
        assertEquals(1, result.size)
        assertEquals(guildId.toString(), result.first().id)
    }

    // ---- getLeaderboard ----

    @Test
    fun `getLeaderboard returns rows sorted by socialCredit with title and voice stats`() {
        val topUser = UserDto(discordId = plainUserId, guildId = guildId).apply {
            socialCredit = 500L
            activeTitleId = 7L
        }
        val midUser = UserDto(discordId = targetUserId, guildId = guildId).apply {
            socialCredit = 200L
            activeTitleId = null
        }
        every { userService.listGuildUsers(guildId) } returns listOf(topUser, midUser)
        mockMember(plainUserId)
        mockMember(targetUserId)
        every { voiceSessionService.sumCountedSecondsLifetimeByUser(guildId) } returns mapOf(
            plainUserId to 7200L,
            targetUserId to 120L
        )
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns mapOf(
            plainUserId to 3600L
        )
        every { snapshotService.listForGuildDate(guildId, any()) } returns listOf(
            MonthlyCreditSnapshotDto(discordId = plainUserId, guildId = guildId, socialCredit = 300L),
            MonthlyCreditSnapshotDto(discordId = targetUserId, guildId = guildId, socialCredit = 200L)
        )
        every { titleService.getById(7L) } returns TitleDto(id = 7L, label = "⭐ Comrade", cost = 100L)

        val rows = service.getLeaderboard(guildId)

        assertEquals(2, rows.size)
        assertEquals(1, rows[0].rank)
        assertEquals(plainUserId.toString(), rows[0].discordId)
        assertEquals(500L, rows[0].socialCredit)
        assertEquals("⭐ Comrade", rows[0].title)
        assertEquals(7200L, rows[0].voiceSecondsLifetime)
        assertEquals(3600L, rows[0].voiceSecondsThisMonth)
        assertEquals(200L, rows[0].creditsEarnedThisMonth)
        assertEquals(2, rows[1].rank)
        assertNull(rows[1].title)
        assertEquals(0L, rows[1].creditsEarnedThisMonth)
        assertEquals("2h 0m", rows[0].voiceLifetimeDisplay)
        assertEquals("1h 0m", rows[0].voiceThisMonthDisplay)
    }

    @Test
    fun `getLeaderboard returns empty list when bot is not in guild`() {
        every { jda.getGuildById(guildId) } returns null
        val rows = service.getLeaderboard(guildId)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `getLeaderboard treats users with no prior snapshot as zero delta`() {
        val user = UserDto(discordId = plainUserId, guildId = guildId).apply { socialCredit = 1_000L }
        every { userService.listGuildUsers(guildId) } returns listOf(user)
        mockMember(plainUserId)
        every { voiceSessionService.sumCountedSecondsLifetimeByUser(guildId) } returns emptyMap()
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { snapshotService.listForGuildDate(guildId, any()) } returns emptyList()
        // Lazy-write returns a baseline DTO whose socialCredit matches current,
        // so the delta for a first-time visit is 0 (consistent with the old
        // behaviour — earnings from 1st-of-month to this visit get absorbed).
        every { snapshotService.upsertIfMissing(any()) } answers { firstArg() }

        val rows = service.getLeaderboard(guildId)
        assertEquals(0L, rows.first().creditsEarnedThisMonth)
    }

    @Test
    fun `getLeaderboard lazy-writes a baseline when none exists for this month`() {
        val user = UserDto(discordId = plainUserId, guildId = guildId).apply {
            socialCredit = 1_000L
            tobyCoins = 12L
        }
        every { userService.listGuildUsers(guildId) } returns listOf(user)
        mockMember(plainUserId)
        every { voiceSessionService.sumCountedSecondsLifetimeByUser(guildId) } returns emptyMap()
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { snapshotService.listForGuildDate(guildId, any()) } returns emptyList()
        val captured = slot<MonthlyCreditSnapshotDto>()
        every { snapshotService.upsertIfMissing(capture(captured)) } answers { firstArg() }

        service.getLeaderboard(guildId)

        // Baseline must capture BOTH counters so the wallet leaderboard is
        // consistent with the social-credit leaderboard. Writing only
        // socialCredit here and leaving tobyCoins = 0 would regress the
        // wallet delta to "all current coins earned this month".
        assertEquals(plainUserId, captured.captured.discordId)
        assertEquals(guildId, captured.captured.guildId)
        assertEquals(1_000L, captured.captured.socialCredit)
        assertEquals(12L, captured.captured.tobyCoins)
    }

    @Test
    fun `getLeaderboard does NOT lazy-write when baseline already exists`() {
        val user = UserDto(discordId = plainUserId, guildId = guildId).apply { socialCredit = 1_050L }
        every { userService.listGuildUsers(guildId) } returns listOf(user)
        mockMember(plainUserId)
        every { voiceSessionService.sumCountedSecondsLifetimeByUser(guildId) } returns emptyMap()
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { snapshotService.listForGuildDate(guildId, any()) } returns listOf(
            MonthlyCreditSnapshotDto(discordId = plainUserId, guildId = guildId, socialCredit = 1_000L)
        )

        val rows = service.getLeaderboard(guildId)

        // Skipping lazy-write is the whole point — scheduled job already
        // wrote the baseline, the user earned 50 since then.
        assertEquals(50L, rows.first().creditsEarnedThisMonth)
        verify(exactly = 0) { snapshotService.upsertIfMissing(any()) }
    }

    @Test
    fun `getLeaderboard subtracts this-month UBI grants from creditsEarnedThisMonth`() {
        val user = UserDto(discordId = plainUserId, guildId = guildId).apply { socialCredit = 1_000L }
        every { userService.listGuildUsers(guildId) } returns listOf(user)
        mockMember(plainUserId)
        every { voiceSessionService.sumCountedSecondsLifetimeByUser(guildId) } returns emptyMap()
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { snapshotService.listForGuildDate(guildId, any()) } returns listOf(
            MonthlyCreditSnapshotDto(discordId = plainUserId, guildId = guildId, socialCredit = 200L)
        )
        // 600 of the 800-credit raw delta came from UBI; effective earnings = 200.
        every { ubiDailyService.sumGrantedInRangeByUser(guildId, any(), any()) } returns mapOf(plainUserId to 600L)

        val rows = service.getLeaderboard(guildId)

        assertEquals(200L, rows.first().creditsEarnedThisMonth)
    }

    @Test
    fun `getLeaderboard clamps at zero when UBI exceeds raw delta`() {
        val user = UserDto(discordId = plainUserId, guildId = guildId).apply { socialCredit = 100L }
        every { userService.listGuildUsers(guildId) } returns listOf(user)
        mockMember(plainUserId)
        every { voiceSessionService.sumCountedSecondsLifetimeByUser(guildId) } returns emptyMap()
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { snapshotService.listForGuildDate(guildId, any()) } returns listOf(
            MonthlyCreditSnapshotDto(discordId = plainUserId, guildId = guildId, socialCredit = 50L)
        )
        // User spent some on the casino; UBI total exceeds the raw delta.
        every { ubiDailyService.sumGrantedInRangeByUser(guildId, any(), any()) } returns mapOf(plainUserId to 200L)

        val rows = service.getLeaderboard(guildId)

        assertEquals(0L, rows.first().creditsEarnedThisMonth, "must not go negative")
    }

    @Test
    fun `getLeaderboard tolerates missing voice tables by returning zeroed voice stats`() {
        val user = UserDto(discordId = plainUserId, guildId = guildId).apply { socialCredit = 500L }
        every { userService.listGuildUsers(guildId) } returns listOf(user)
        mockMember(plainUserId)
        every { voiceSessionService.sumCountedSecondsLifetimeByUser(guildId) } throws
            RuntimeException("relation \"voice_session\" does not exist")
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } throws
            RuntimeException("relation \"voice_session\" does not exist")
        every { snapshotService.listForGuildDate(guildId, any()) } throws
            RuntimeException("relation \"monthly_credit_snapshot\" does not exist")
        // Lazy-write would hit the same missing table; simulate that so the
        // assertion reflects real-world behaviour.
        every { snapshotService.upsertIfMissing(any()) } throws
            RuntimeException("relation \"monthly_credit_snapshot\" does not exist")

        val rows = service.getLeaderboard(guildId)

        assertEquals(1, rows.size)
        assertEquals(500L, rows[0].socialCredit)
        assertEquals(0L, rows[0].voiceSecondsLifetime)
        assertEquals(0L, rows[0].voiceSecondsThisMonth)
        assertEquals(0L, rows[0].creditsEarnedThisMonth)
    }

    @Test
    fun `getLeaderboard tolerates missing title rows without breaking the whole page`() {
        val user = UserDto(discordId = plainUserId, guildId = guildId).apply {
            socialCredit = 100L
            activeTitleId = 42L
        }
        every { userService.listGuildUsers(guildId) } returns listOf(user)
        mockMember(plainUserId)
        every { voiceSessionService.sumCountedSecondsLifetimeByUser(guildId) } returns emptyMap()
        every { voiceSessionService.sumCountedSecondsInRangeByUser(guildId, any(), any()) } returns emptyMap()
        every { snapshotService.listForGuildDate(guildId, any()) } returns emptyList()
        every { titleService.getById(42L) } throws
            RuntimeException("relation \"title\" does not exist")

        val rows = service.getLeaderboard(guildId)

        assertEquals(1, rows.size)
        assertNull(rows[0].title)
    }


    // ---- updateConfig LEADERBOARD_CHANNEL ----

    @Test
    fun `updateConfig LEADERBOARD_CHANNEL rejects non-numeric id`() {
        mockMember(ownerId, isOwner = true)
        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.LEADERBOARD_CHANNEL, "not-a-number")
        assertEquals("Channel id must be numeric.", err)
    }

    @Test
    fun `updateConfig LEADERBOARD_CHANNEL rejects unknown channel id`() {
        mockMember(ownerId, isOwner = true)
        every { guild.getTextChannelById(111L) } returns null
        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.LEADERBOARD_CHANNEL, "111")
        assertEquals("No text channel with that id exists in this server.", err)
    }

    @Test
    fun `updateConfig LEADERBOARD_CHANNEL persists valid channel id`() {
        mockMember(ownerId, isOwner = true)
        val textChannel = mockk<TextChannel>(relaxed = true)
        every { guild.getTextChannelById(111L) } returns textChannel

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.LEADERBOARD_CHANNEL, "111")
        assertNull(err)
        verify(exactly = 1) {
            configService.upsertConfig("LEADERBOARD_CHANNEL", "111", guildId.toString())
        }
    }

    @Test
    fun `updateConfig ACTIVITY_TRACKING=true publishes the first-enable event`() {
        mockMember(ownerId, isOwner = true)

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.ACTIVITY_TRACKING, "true")

        assertNull(err)
        // Event must fire regardless of previous value — the listener
        // de-duplicates via ACTIVITY_TRACKING_NOTIFIED, so publishing on
        // every "true" write is the right contract.
        verify(exactly = 1) {
            eventPublisher.publishEvent(common.events.ActivityTrackingEnabled(guildId))
        }
    }

    @Test
    fun `updateConfig ACTIVITY_TRACKING=false does NOT publish the event`() {
        mockMember(ownerId, isOwner = true)

        service.updateConfig(ownerId, guildId, ConfigDto.Configurations.ACTIVITY_TRACKING, "false")

        // Disabling tracking must never trigger member DMs.
        verify(exactly = 0) {
            eventPublisher.publishEvent(any<common.events.ActivityTrackingEnabled>())
        }
    }

    @Test
    fun `updateConfig ACTIVITY_TRACKING rejects non-boolean values`() {
        mockMember(ownerId, isOwner = true)

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.ACTIVITY_TRACKING, "maybe")

        assertEquals("Value must be true or false.", err)
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
        verify(exactly = 0) {
            eventPublisher.publishEvent(any<common.events.ActivityTrackingEnabled>())
        }
    }

    @Test
    fun `updateConfig JACKPOT_WIN_PCT persists a sub-1 percent decimal`() {
        mockMember(ownerId, isOwner = true)

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.JACKPOT_WIN_PCT, "0.5")

        assertNull(err)
        verify(exactly = 1) {
            configService.upsertConfig("JACKPOT_WIN_PCT", "0.5", guildId.toString())
        }
    }

    @Test
    fun `updateConfig JACKPOT_WIN_PCT persists a whole-number percent`() {
        mockMember(ownerId, isOwner = true)

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.JACKPOT_WIN_PCT, "5")

        assertNull(err)
        verify(exactly = 1) {
            configService.upsertConfig("JACKPOT_WIN_PCT", any(), guildId.toString())
        }
    }

    @Test
    fun `updateConfig JACKPOT_WIN_PCT rejects out-of-range value`() {
        mockMember(ownerId, isOwner = true)

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.JACKPOT_WIN_PCT, "75")

        assertEquals("Value must be a number between 0 and 50 (decimals allowed; default 1).", err)
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
    }

    @Test
    fun `updateConfig JACKPOT_WIN_PCT rejects unparseable value`() {
        mockMember(ownerId, isOwner = true)

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.JACKPOT_WIN_PCT, "abc")

        assertEquals("Value must be a number between 0 and 50 (decimals allowed; default 1).", err)
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
    }

    @Test
    fun `updateConfig TRADE_BUY_FEE_PCT persists a decimal value`() {
        mockMember(ownerId, isOwner = true)

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.TRADE_BUY_FEE_PCT, "0.5")

        assertNull(err)
        verify(exactly = 1) {
            configService.upsertConfig("TRADE_BUY_FEE_PCT", "0.5", guildId.toString())
        }
    }

    @Test
    fun `updateConfig TRADE_SELL_FEE_PCT persists a decimal value`() {
        mockMember(ownerId, isOwner = true)

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.TRADE_SELL_FEE_PCT, "2.5")

        assertNull(err)
        verify(exactly = 1) {
            configService.upsertConfig("TRADE_SELL_FEE_PCT", "2.5", guildId.toString())
        }
    }

    @Test
    fun `updateConfig TRADE_BUY_FEE_PCT rejects out-of-range value`() {
        mockMember(ownerId, isOwner = true)

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.TRADE_BUY_FEE_PCT, "30")

        assertEquals("Value must be a number between 0 and 25 (decimals allowed; default 1).", err)
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
    }

    @Test
    fun `updateConfig TRADE_SELL_FEE_PCT rejects unparseable value`() {
        mockMember(ownerId, isOwner = true)

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.TRADE_SELL_FEE_PCT, "abc")

        assertEquals("Value must be a number between 0 and 25 (decimals allowed; default 1).", err)
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
    }

    @Test
    fun `updateConfig UBI_DAILY_AMOUNT persists valid value`() {
        mockMember(ownerId, isOwner = true)

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.UBI_DAILY_AMOUNT, "50")

        assertNull(err)
        verify(exactly = 1) {
            configService.upsertConfig("UBI_DAILY_AMOUNT", "50", guildId.toString())
        }
    }

    @Test
    fun `updateConfig UBI_DAILY_AMOUNT rejects out-of-range value`() {
        mockMember(ownerId, isOwner = true)

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.UBI_DAILY_AMOUNT, "5000")

        assertEquals("Value must be between 0 and 1000 (0 disables UBI).", err)
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
    }

    @Test
    fun `updateConfig DAILY_CREDIT_CAP persists valid value`() {
        mockMember(ownerId, isOwner = true)

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.DAILY_CREDIT_CAP, "200")

        assertNull(err)
        verify(exactly = 1) {
            configService.upsertConfig("DAILY_CREDIT_CAP", "200", guildId.toString())
        }
    }

    @Test
    fun `updateConfig DAILY_CREDIT_CAP rejects negative value`() {
        mockMember(ownerId, isOwner = true)

        val err = service.updateConfig(ownerId, guildId, ConfigDto.Configurations.DAILY_CREDIT_CAP, "-5")

        assertEquals("Value must be between 0 and 10000 (default 90).", err)
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
    }

    @Test
    fun `updateConfig CASINO_MODLOG_CHANNEL_ID accepts a valid text channel id`() {
        mockMember(ownerId, isOwner = true)
        val channel = mockk<TextChannel>(relaxed = true)
        every { channel.id } returns "12345"
        every { guild.getTextChannelById(12345L) } returns channel

        val err = service.updateConfig(
            ownerId, guildId, ConfigDto.Configurations.CASINO_MODLOG_CHANNEL_ID, "12345"
        )

        assertNull(err)
        verify(exactly = 1) {
            configService.upsertConfig("CASINO_MODLOG_CHANNEL_ID", "12345", guildId.toString())
        }
    }

    @Test
    fun `updateConfig CASINO_MODLOG_CHANNEL_ID empty value clears the override`() {
        mockMember(ownerId, isOwner = true)

        val err = service.updateConfig(
            ownerId, guildId, ConfigDto.Configurations.CASINO_MODLOG_CHANNEL_ID, ""
        )

        assertNull(err)
        verify(exactly = 1) {
            configService.upsertConfig("CASINO_MODLOG_CHANNEL_ID", "", guildId.toString())
        }
    }

    @Test
    fun `updateConfig CASINO_MODLOG_CHANNEL_ID rejects non-numeric value`() {
        mockMember(ownerId, isOwner = true)

        val err = service.updateConfig(
            ownerId, guildId, ConfigDto.Configurations.CASINO_MODLOG_CHANNEL_ID, "not-a-number"
        )

        assertEquals("Channel id must be numeric.", err)
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
    }

    @Test
    fun `updateConfig CASINO_MODLOG_CHANNEL_ID rejects unknown channel id`() {
        mockMember(ownerId, isOwner = true)
        every { guild.getTextChannelById(99999L) } returns null

        val err = service.updateConfig(
            ownerId, guildId, ConfigDto.Configurations.CASINO_MODLOG_CHANNEL_ID, "99999"
        )

        assertEquals("No text channel with that id exists in this server.", err)
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
    }

    @Test
    fun `getLeaderboard queries voice for the CURRENT month, not the previous one`() {
        // Regression: the old code passed [prevMonthStart, thisMonthStart),
        // which is the just-finished month — copy-paste from
        // MonthlyLeaderboardJob (which runs on the 1st and reports the
        // previous month). The live web view should query [thisMonthStart,
        // nextMonthStart) so today's sessions actually count.
        val user = UserDto(discordId = plainUserId, guildId = guildId).apply { socialCredit = 100L }
        every { userService.listGuildUsers(guildId) } returns listOf(user)
        mockMember(plainUserId)
        every { voiceSessionService.sumCountedSecondsLifetimeByUser(guildId) } returns emptyMap()
        every { snapshotService.listForGuildDate(guildId, any()) } returns emptyList()

        val fromCaptor = slot<java.time.Instant>()
        val untilCaptor = slot<java.time.Instant>()
        every {
            voiceSessionService.sumCountedSecondsInRangeByUser(
                guildId,
                capture(fromCaptor),
                capture(untilCaptor)
            )
        } returns emptyMap()

        service.getLeaderboard(guildId)

        val now = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        val expectedFrom = now.withDayOfMonth(1)
            .atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
        val expectedUntil = now.withDayOfMonth(1).plusMonths(1)
            .atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
        assertEquals(expectedFrom, fromCaptor.captured,
            "voice range must START at this month's 1st, not last month's 1st")
        assertEquals(expectedUntil, untilCaptor.captured,
            "voice range must END at next month's 1st, not this month's 1st")
    }

    // ===================================================================
    // createReadOnlyChannel
    // ===================================================================

    private fun stubBotPerms(
        canManageChannels: Boolean,
        canManageRoles: Boolean = canManageChannels,
    ) {
        // MANAGE_ROLES is gated separately from MANAGE_CHANNEL in
        // Discord — applying permission overrides (which both create
        // flows always do) requires MANAGE_ROLES even when MANAGE_CHANNEL
        // is granted. Default to the same value as canManageChannels
        // so the bulk of existing tests stay happy-path; explicit
        // false lets us pin the per-permission rejection messages.
        val bot = mockk<SelfMember>(relaxed = true)
        every { bot.idLong } returns 999L
        every { bot.hasPermission(Permission.MANAGE_CHANNEL) } returns canManageChannels
        every { bot.hasPermission(Permission.MANAGE_ROLES) } returns canManageRoles
        every { guild.selfMember } returns bot
    }

    private fun stubChannelCreation(name: String, newId: Long): TextChannel {
        // Stubbing JDA's generic fluent chain `guild.createTextChannel(name)
        //     .addRolePermissionOverride(...)
        //     .addMemberPermissionOverride(...)
        //     .setParent(...)?
        //     .complete()` is hostile to mockk: recording
        // `every { action.complete() } returns ...` against the cast
        // `ChannelAction<TextChannel>` triggers a Kotlin-inserted checkcast
        // on the relaxed mock's auto-returned `GuildChannel$Subclass16`.
        // Recording on the star-projected `rawAction` instead — where
        // `complete()` returns `Object` — sidesteps the cast at recording
        // time. Production code's call site gets the right object back.
        val newChannel = mockk<TextChannel>(relaxed = true)
        every { newChannel.id } returns newId.toString()
        every { newChannel.name } returns name
        val rawAction = mockk<net.dv8tion.jda.api.requests.restaction.ChannelAction<*>>(relaxed = true)
        every {
            rawAction.addRolePermissionOverride(any<Long>(), any<Collection<Permission>>(), any<Collection<Permission>>())
        } returns rawAction
        every {
            rawAction.addMemberPermissionOverride(any<Long>(), any<Collection<Permission>>(), any<Collection<Permission>>())
        } returns rawAction
        every { rawAction.setParent(any()) } returns rawAction
        every { rawAction.complete() } returns newChannel
        @Suppress("UNCHECKED_CAST")
        val action = rawAction as net.dv8tion.jda.api.requests.restaction.ChannelAction<TextChannel>
        every { guild.createTextChannel(name) } returns action
        every { guild.publicRole.idLong } returns 1L
        return newChannel
    }

    @Test
    fun `createReadOnlyChannel rejects non-moderator caller`() {
        mockMember(plainUserId, isOwner = false)

        val r = service.createReadOnlyChannel(
            actorDiscordId = plainUserId,
            guildId = guildId,
            rawName = "lottery-results",
            targetConfigName = "LOTTERY_CHANNEL",
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Error)
        r as ModerationWebService.CreateChannelOutcome.Error
        assertTrue(r.message.contains("Not allowed"))
        // No JDA mutation attempted.
        verify(exactly = 0) { guild.createTextChannel(any<String>()) }
    }

    @Test
    fun `createReadOnlyChannel rejects non-allow-listed targetConfig`() {
        mockMember(ownerId, isOwner = true)

        val r = service.createReadOnlyChannel(
            actorDiscordId = ownerId,
            guildId = guildId,
            rawName = "x",
            // Real enum entry but NOT in CHANNEL_CONFIG_ALLOWLIST.
            targetConfigName = "JACKPOT_WHEEL_SEGMENTS",
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Error)
        r as ModerationWebService.CreateChannelOutcome.Error
        assertTrue(r.message.contains("Unknown channel config"))
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
    }

    @Test
    fun `createReadOnlyChannel rejects unknown targetConfig`() {
        mockMember(ownerId, isOwner = true)

        val r = service.createReadOnlyChannel(
            actorDiscordId = ownerId,
            guildId = guildId,
            rawName = "x",
            targetConfigName = "TOTALLY_MADE_UP",
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Error)
    }

    @Test
    fun `createReadOnlyChannel rejects when bot lacks MANAGE_CHANNEL`() {
        mockMember(ownerId, isOwner = true)
        stubBotPerms(canManageChannels = false)

        val r = service.createReadOnlyChannel(
            actorDiscordId = ownerId,
            guildId = guildId,
            rawName = "lottery-results",
            targetConfigName = "LOTTERY_CHANNEL",
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Error)
        r as ModerationWebService.CreateChannelOutcome.Error
        assertTrue(r.message.contains("Manage Channels"))
    }

    @Test
    fun `createReadOnlyChannel rejects when bot has MANAGE_CHANNEL but not MANAGE_ROLES`() {
        // Real-world case that produced Discord error 50013 Missing
        // Permissions: bot can create the channel itself but Discord
        // gates the override-set step on MANAGE_ROLES. The check must
        // surface a clear actionable message before we ever hit JDA.
        mockMember(ownerId, isOwner = true)
        stubBotPerms(canManageChannels = true, canManageRoles = false)

        val r = service.createReadOnlyChannel(
            actorDiscordId = ownerId,
            guildId = guildId,
            rawName = "lottery-results",
            targetConfigName = "LOTTERY_CHANNEL",
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Error)
        r as ModerationWebService.CreateChannelOutcome.Error
        assertTrue(
            r.message.contains("Manage Roles"),
            "expected actionable Manage Roles message, got: ${r.message}",
        )
        // Never reached JDA.
        verify(exactly = 0) { guild.createTextChannel(any<String>()) }
    }

    @Test
    fun `createAdminOnlyChannel rejects when bot has MANAGE_CHANNEL but not MANAGE_ROLES`() {
        // Same gate applies — the admin-only flow's deny-VIEW_CHANNEL
        // override needs MANAGE_ROLES too.
        mockMember(ownerId, isOwner = true)
        stubBotPerms(canManageChannels = true, canManageRoles = false)

        val r = service.createAdminOnlyChannel(
            actorDiscordId = ownerId,
            guildId = guildId,
            rawName = "casino-modlog",
            targetConfigName = "CASINO_MODLOG_CHANNEL_ID",
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Error)
        r as ModerationWebService.CreateChannelOutcome.Error
        assertTrue(r.message.contains("Manage Roles"))
        verify(exactly = 0) { guild.createTextChannel(any<String>()) }
    }

    @Test
    fun `createReadOnlyChannel rejects empty or all-special-chars name`() {
        mockMember(ownerId, isOwner = true)
        stubBotPerms(canManageChannels = true)

        val r = service.createReadOnlyChannel(
            actorDiscordId = ownerId, guildId = guildId,
            rawName = "!!!", targetConfigName = "LOTTERY_CHANNEL",
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Error)
        r as ModerationWebService.CreateChannelOutcome.Error
        assertTrue(r.message.contains("1-90 chars"))

        val r2 = service.createReadOnlyChannel(
            actorDiscordId = ownerId, guildId = guildId,
            rawName = "   ", targetConfigName = "LOTTERY_CHANNEL",
        )
        assertTrue(r2 is ModerationWebService.CreateChannelOutcome.Error)
    }

    @Test
    fun `createReadOnlyChannel happy path for LOTTERY_CHANNEL`() {
        mockMember(ownerId, isOwner = true)
        stubBotPerms(canManageChannels = true)
        stubChannelCreation(name = "lottery-results", newId = 555L)

        val r = service.createReadOnlyChannel(
            actorDiscordId = ownerId,
            guildId = guildId,
            rawName = "Lottery Results 🎉",  // gets sanitised to "lottery-results"
            targetConfigName = "LOTTERY_CHANNEL",
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Ok)
        r as ModerationWebService.CreateChannelOutcome.Ok
        assertEquals("555", r.channelId)
        assertEquals("lottery-results", r.channelName)
        assertEquals("LOTTERY_CHANNEL", r.targetConfig)
        verify(exactly = 1) {
            configService.upsertConfig("LOTTERY_CHANNEL", "555", guildId.toString())
        }
    }

    @Test
    fun `createReadOnlyChannel happy path for LEADERBOARD_CHANNEL`() {
        mockMember(ownerId, isOwner = true)
        stubBotPerms(canManageChannels = true)
        stubChannelCreation(name = "leaderboard", newId = 777L)

        val r = service.createReadOnlyChannel(
            actorDiscordId = ownerId,
            guildId = guildId,
            rawName = "leaderboard",
            targetConfigName = "LEADERBOARD_CHANNEL",
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Ok)
        r as ModerationWebService.CreateChannelOutcome.Ok
        assertEquals("LEADERBOARD_CHANNEL", r.targetConfig)
        verify(exactly = 1) {
            configService.upsertConfig("LEADERBOARD_CHANNEL", "777", guildId.toString())
        }
    }

    @Test
    fun `createReadOnlyChannel happy path with existing parent category`() {
        mockMember(ownerId, isOwner = true)
        stubBotPerms(canManageChannels = true)
        stubChannelCreation(name = "lottery-results", newId = 555L)

        val parent = mockk<net.dv8tion.jda.api.entities.channel.concrete.Category>(relaxed = true)
        every { guild.getCategoryById(42L) } returns parent

        val r = service.createReadOnlyChannel(
            actorDiscordId = ownerId,
            guildId = guildId,
            rawName = "lottery-results",
            targetConfigName = "LOTTERY_CHANNEL",
            parentCategoryId = "42",
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Ok)
    }

    @Test
    fun `createReadOnlyChannel rejects invalid parent category id`() {
        mockMember(ownerId, isOwner = true)
        stubBotPerms(canManageChannels = true)
        every { guild.getCategoryById(any<Long>()) } returns null

        val r = service.createReadOnlyChannel(
            actorDiscordId = ownerId,
            guildId = guildId,
            rawName = "x",
            targetConfigName = "LOTTERY_CHANNEL",
            parentCategoryId = "9999",
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Error)
        r as ModerationWebService.CreateChannelOutcome.Error
        assertTrue(r.message.contains("No category"))
    }

    @Test
    fun `createReadOnlyChannel rejects non-numeric parent category id`() {
        mockMember(ownerId, isOwner = true)
        stubBotPerms(canManageChannels = true)

        val r = service.createReadOnlyChannel(
            actorDiscordId = ownerId,
            guildId = guildId,
            rawName = "x",
            targetConfigName = "LOTTERY_CHANNEL",
            parentCategoryId = "not-a-number",
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Error)
        r as ModerationWebService.CreateChannelOutcome.Error
        assertTrue(r.message.contains("Invalid category id"))
    }

    @Test
    fun `createReadOnlyChannel happy path creates a new category`() {
        mockMember(ownerId, isOwner = true)
        stubBotPerms(canManageChannels = true)
        stubChannelCreation(name = "lottery-results", newId = 555L)

        // Stub guild.createCategory("Lottery").complete() chain.
        // Same star-projection trick as stubChannelCreation — record on the
        // raw mock to avoid the generic checkcast at recording time.
        val newCategory = mockk<net.dv8tion.jda.api.entities.channel.concrete.Category>(relaxed = true)
        val rawCatAction = mockk<net.dv8tion.jda.api.requests.restaction.ChannelAction<*>>(relaxed = true)
        every { rawCatAction.complete() } returns newCategory
        @Suppress("UNCHECKED_CAST")
        val catAction = rawCatAction as net.dv8tion.jda.api.requests.restaction.ChannelAction<net.dv8tion.jda.api.entities.channel.concrete.Category>
        every { guild.createCategory("Lottery") } returns catAction

        val r = service.createReadOnlyChannel(
            actorDiscordId = ownerId,
            guildId = guildId,
            rawName = "lottery-results",
            targetConfigName = "LOTTERY_CHANNEL",
            newCategoryName = "Lottery",
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Ok)
        // New-category path was taken — verify createCategory was called.
        verify(exactly = 1) { guild.createCategory("Lottery") }
    }

    @Test
    fun `createReadOnlyChannel new-category-name takes precedence over parentCategoryId`() {
        mockMember(ownerId, isOwner = true)
        stubBotPerms(canManageChannels = true)
        stubChannelCreation(name = "lottery-results", newId = 555L)

        val newCategory = mockk<net.dv8tion.jda.api.entities.channel.concrete.Category>(relaxed = true)
        val rawCatAction = mockk<net.dv8tion.jda.api.requests.restaction.ChannelAction<*>>(relaxed = true)
        every { rawCatAction.complete() } returns newCategory
        @Suppress("UNCHECKED_CAST")
        val catAction = rawCatAction as net.dv8tion.jda.api.requests.restaction.ChannelAction<net.dv8tion.jda.api.entities.channel.concrete.Category>
        every { guild.createCategory("New Cat") } returns catAction

        // Both fields supplied: new-category should win, the dropdown id ignored.
        val r = service.createReadOnlyChannel(
            actorDiscordId = ownerId,
            guildId = guildId,
            rawName = "lottery-results",
            targetConfigName = "LOTTERY_CHANNEL",
            parentCategoryId = "42",            // would be looked up if used
            newCategoryName = "New Cat",
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Ok)
        verify(exactly = 1) { guild.createCategory("New Cat") }
        verify(exactly = 0) { guild.getCategoryById(any<Long>()) }
    }

    @Test
    fun `createReadOnlyChannel rejects too-long new category name`() {
        mockMember(ownerId, isOwner = true)
        stubBotPerms(canManageChannels = true)

        val r = service.createReadOnlyChannel(
            actorDiscordId = ownerId,
            guildId = guildId,
            rawName = "x",
            targetConfigName = "LOTTERY_CHANNEL",
            newCategoryName = "a".repeat(101),
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Error)
        r as ModerationWebService.CreateChannelOutcome.Error
        assertTrue(r.message.contains("Category name"))
    }

    @Test
    fun `createReadOnlyChannel surfaces JDA failure as Error`() {
        mockMember(ownerId, isOwner = true)
        stubBotPerms(canManageChannels = true)
        val rawAction = mockk<net.dv8tion.jda.api.requests.restaction.ChannelAction<*>>(relaxed = true)
        every {
            rawAction.addRolePermissionOverride(any<Long>(), any<Collection<Permission>>(), any<Collection<Permission>>())
        } returns rawAction
        every {
            rawAction.addMemberPermissionOverride(any<Long>(), any<Collection<Permission>>(), any<Collection<Permission>>())
        } returns rawAction
        every { rawAction.setParent(any()) } returns rawAction
        every { rawAction.complete() } throws RuntimeException("rate limited")
        @Suppress("UNCHECKED_CAST")
        val action = rawAction as net.dv8tion.jda.api.requests.restaction.ChannelAction<TextChannel>
        every { guild.createTextChannel("lottery-results") } returns action
        every { guild.publicRole.idLong } returns 1L

        val r = service.createReadOnlyChannel(
            actorDiscordId = ownerId, guildId = guildId,
            rawName = "lottery-results", targetConfigName = "LOTTERY_CHANNEL",
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Error)
        r as ModerationWebService.CreateChannelOutcome.Error
        assertTrue(r.message.contains("rate limited"))
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
    }

    // ===================================================================
    // createAdminOnlyChannel — mirrors createReadOnlyChannel but flips
    // the @everyone permission shape from "see-but-no-write" to
    // "denied VIEW_CHANNEL" and uses a separate allow-list so a
    // public-facing config can't accidentally route through here.
    // ===================================================================

    @Test
    fun `createAdminOnlyChannel rejects non-moderator caller`() {
        mockMember(plainUserId, isOwner = false)

        val r = service.createAdminOnlyChannel(
            actorDiscordId = plainUserId,
            guildId = guildId,
            rawName = "casino-modlog",
            targetConfigName = "CASINO_MODLOG_CHANNEL_ID",
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Error)
        verify(exactly = 0) { guild.createTextChannel(any<String>()) }
    }

    @Test
    fun `createAdminOnlyChannel rejects a public-facing config not on the admin allow-list`() {
        // LOTTERY_CHANNEL is on the public allow-list but NOT the
        // admin one — routing it through this endpoint would invert
        // its visibility (denied to @everyone), defeating the lottery
        // announce flow. Defence in depth on top of the form's
        // data-target-config attribute.
        mockMember(ownerId, isOwner = true)

        val r = service.createAdminOnlyChannel(
            actorDiscordId = ownerId,
            guildId = guildId,
            rawName = "casino-modlog",
            targetConfigName = "LOTTERY_CHANNEL",
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Error)
        r as ModerationWebService.CreateChannelOutcome.Error
        assertTrue(r.message.contains("Unknown channel config"))
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
    }

    @Test
    fun `createAdminOnlyChannel happy path for CASINO_MODLOG_CHANNEL_ID upserts config`() {
        mockMember(ownerId, isOwner = true)
        stubBotPerms(canManageChannels = true)
        stubChannelCreation(name = "casino-modlog", newId = 4242L)

        val r = service.createAdminOnlyChannel(
            actorDiscordId = ownerId,
            guildId = guildId,
            rawName = "casino-modlog",
            targetConfigName = "CASINO_MODLOG_CHANNEL_ID",
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Ok)
        r as ModerationWebService.CreateChannelOutcome.Ok
        assertEquals("4242", r.channelId)
        assertEquals("casino-modlog", r.channelName)
        assertEquals("CASINO_MODLOG_CHANNEL_ID", r.targetConfig)

        verify(exactly = 1) {
            configService.upsertConfig(
                "CASINO_MODLOG_CHANNEL_ID", "4242", guildId.toString(),
            )
        }
        // Permission-override verification skipped — the stubbed
        // ChannelAction chain mockk can match on `any<Collection>` but
        // not deeply equate the deny list against
        // listOf(Permission.VIEW_CHANNEL). The allow-list filter test
        // (`createAdminOnlyChannel rejects a public-facing config…`)
        // is the primary guard against routing the wrong config
        // through the wrong visibility shape.
    }

    @Test
    fun `createAdminOnlyChannel rejects when bot lacks MANAGE_CHANNEL`() {
        mockMember(ownerId, isOwner = true)
        stubBotPerms(canManageChannels = false)

        val r = service.createAdminOnlyChannel(
            actorDiscordId = ownerId,
            guildId = guildId,
            rawName = "casino-modlog",
            targetConfigName = "CASINO_MODLOG_CHANNEL_ID",
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Error)
        r as ModerationWebService.CreateChannelOutcome.Error
        assertTrue(r.message.contains("Manage Channels"))
    }

    @Test
    fun `createReadOnlyChannel rejects an admin-only config not on the public allow-list`() {
        // Symmetric defence — CASINO_MODLOG_CHANNEL_ID must not be
        // routable through the public read-only endpoint. Otherwise
        // the channel would be visible to @everyone, defeating the
        // private mod-log purpose.
        mockMember(ownerId, isOwner = true)

        val r = service.createReadOnlyChannel(
            actorDiscordId = ownerId,
            guildId = guildId,
            rawName = "casino-modlog",
            targetConfigName = "CASINO_MODLOG_CHANNEL_ID",
        )
        assertTrue(r is ModerationWebService.CreateChannelOutcome.Error)
        r as ModerationWebService.CreateChannelOutcome.Error
        assertTrue(r.message.contains("Unknown channel config"))
        verify(exactly = 0) { configService.upsertConfig(any(), any(), any()) }
    }

    // ---- sanitizeChannelName ----

    @Test
    fun `sanitizeChannelName lowercases, dashes, and trims`() {
        assertEquals("lottery-results", service.sanitizeChannelName("Lottery Results"))
        assertEquals("lottery-results", service.sanitizeChannelName("Lottery Results 🎉"))
        assertEquals("lottery-results", service.sanitizeChannelName("--lottery--results--"))
        assertEquals("a-b-c", service.sanitizeChannelName("a@b@c"))
    }

    @Test
    fun `sanitizeChannelName returns null for empty or all-special input`() {
        assertNull(service.sanitizeChannelName(""))
        assertNull(service.sanitizeChannelName("   "))
        assertNull(service.sanitizeChannelName("!!!"))
        assertNull(service.sanitizeChannelName("---"))
    }

    @Test
    fun `sanitizeChannelName caps at 90 chars`() {
        val long = "a".repeat(200)
        val result = service.sanitizeChannelName(long)
        assertNotNull(result)
        assertEquals(90, result!!.length)
    }

    // ---- leveling moderation ----

    @Test
    fun `upsertLevelReward rejects non-owner actor`() {
        mockMember(superUserId, isOwner = false)
        every { introWebService.isSuperUser(superUserId, guildId) } returns true

        val err = service.upsertLevelReward(superUserId, guildId, level = 2, roleId = 999L)

        assertEquals("Only the server owner can change guild config.", err)
        verify(exactly = 0) { levelRoleRewardService.upsert(any()) }
    }

    @Test
    fun `upsertLevelReward rejects level zero`() {
        mockMember(ownerId, isOwner = true)

        val err = service.upsertLevelReward(ownerId, guildId, level = 0, roleId = 999L)

        assertEquals("Level must be 1 or higher.", err)
        verify(exactly = 0) { levelRoleRewardService.upsert(any()) }
    }

    @Test
    fun `upsertLevelReward rejects unknown role`() {
        mockMember(ownerId, isOwner = true)
        every { guild.getRoleById(999L) } returns null

        val err = service.upsertLevelReward(ownerId, guildId, level = 2, roleId = 999L)

        assertEquals("Role not found in this server.", err)
        verify(exactly = 0) { levelRoleRewardService.upsert(any()) }
    }

    @Test
    fun `upsertLevelReward rejects managed role`() {
        mockMember(ownerId, isOwner = true)
        val role = mockk<net.dv8tion.jda.api.entities.Role>(relaxed = true).also {
            every { it.isManaged } returns true
        }
        every { guild.getRoleById(999L) } returns role

        val err = service.upsertLevelReward(ownerId, guildId, level = 2, roleId = 999L)

        assertEquals(
            "That role is managed by an integration and can't be assigned by the bot.",
            err
        )
    }

    @Test
    fun `upsertLevelReward rejects role above bot hierarchy`() {
        mockMember(ownerId, isOwner = true)
        val role = mockk<net.dv8tion.jda.api.entities.Role>(relaxed = true).also {
            every { it.isManaged } returns false
            every { it.name } returns "Top Tier"
        }
        every { guild.getRoleById(999L) } returns role
        val selfMember = mockk<SelfMember>(relaxed = true).also {
            every { it.canInteract(role) } returns false
        }
        every { guild.selfMember } returns selfMember

        val err = service.upsertLevelReward(ownerId, guildId, level = 2, roleId = 999L)

        assertNotNull(err)
        assertTrue(err!!.contains("Top Tier"))
    }

    @Test
    fun `upsertLevelReward saves valid reward`() {
        mockMember(ownerId, isOwner = true)
        val role = mockk<net.dv8tion.jda.api.entities.Role>(relaxed = true).also {
            every { it.isManaged } returns false
        }
        every { guild.getRoleById(999L) } returns role
        val selfMember = mockk<SelfMember>(relaxed = true).also {
            every { it.canInteract(role) } returns true
        }
        every { guild.selfMember } returns selfMember

        val err = service.upsertLevelReward(ownerId, guildId, level = 5, roleId = 999L)

        assertNull(err)
        val captured = slot<database.dto.LevelRoleRewardDto>()
        verify(exactly = 1) { levelRoleRewardService.upsert(capture(captured)) }
        assertEquals(guildId, captured.captured.guildId)
        assertEquals(5, captured.captured.level)
        assertEquals(999L, captured.captured.roleId)
    }

    @Test
    fun `deleteLevelReward rejects non-owner actor`() {
        mockMember(superUserId, isOwner = false)
        every { introWebService.isSuperUser(superUserId, guildId) } returns true

        val err = service.deleteLevelReward(superUserId, guildId, level = 2)

        assertEquals("Only the server owner can change guild config.", err)
        verify(exactly = 0) { levelRoleRewardService.delete(any(), any()) }
    }

    @Test
    fun `deleteLevelReward succeeds for owner`() {
        mockMember(ownerId, isOwner = true)

        val err = service.deleteLevelReward(ownerId, guildId, level = 5)

        assertNull(err)
        verify(exactly = 1) { levelRoleRewardService.delete(guildId, 5) }
    }

    @Test
    fun `setTitleRequiredLevel rejects non-owner actor`() {
        mockMember(superUserId, isOwner = false)
        every { introWebService.isSuperUser(superUserId, guildId) } returns true

        val err = service.setTitleRequiredLevel(superUserId, guildId, titleId = 7L, requiredLevel = 3)

        assertEquals("Only the server owner can change guild config.", err)
        verify(exactly = 0) { titleService.updateRequiredLevel(any(), any()) }
    }

    @Test
    fun `setTitleRequiredLevel rejects negative level`() {
        mockMember(ownerId, isOwner = true)

        val err = service.setTitleRequiredLevel(ownerId, guildId, titleId = 7L, requiredLevel = -1)

        assertEquals("Required level must be zero or higher.", err)
    }

    @Test
    fun `setTitleRequiredLevel surfaces title-not-found`() {
        mockMember(ownerId, isOwner = true)
        every { titleService.updateRequiredLevel(7L, 3) } returns null

        val err = service.setTitleRequiredLevel(ownerId, guildId, titleId = 7L, requiredLevel = 3)

        assertEquals("Title not found.", err)
    }

    @Test
    fun `setTitleRequiredLevel persists when valid`() {
        mockMember(ownerId, isOwner = true)
        val updated = TitleDto(id = 7L, label = "Speaker", cost = 500L).apply { requiredLevel = 5 }
        every { titleService.updateRequiredLevel(7L, 5) } returns updated

        val err = service.setTitleRequiredLevel(ownerId, guildId, titleId = 7L, requiredLevel = 5)

        assertNull(err)
        verify(exactly = 1) { titleService.updateRequiredLevel(7L, 5) }
    }

    @Test
    fun `getLevelingOverview surfaces missing-role flag for dangling rewards`() {
        every { levelRoleRewardService.listForGuild(guildId) } returns listOf(
            database.dto.LevelRoleRewardDto(guildId = guildId, level = 5, roleId = 555L),
            database.dto.LevelRoleRewardDto(guildId = guildId, level = 10, roleId = 666L),
        )
        val liveRole = mockk<net.dv8tion.jda.api.entities.Role>(relaxed = true).also {
            every { it.name } returns "Veteran"
            every { it.colorRaw } returns 0
        }
        every { guild.getRoleById(555L) } returns liveRole
        every { guild.getRoleById(666L) } returns null
        every { guild.roles } returns emptyList()
        every { titleService.listAll() } returns emptyList()

        val overview = service.getLevelingOverview(guildId)

        assertNotNull(overview)
        assertEquals(2, overview!!.levelRewards.size)
        assertFalse(overview.levelRewards[0].roleMissing)
        assertEquals("Veteran", overview.levelRewards[0].roleName)
        assertTrue(overview.levelRewards[1].roleMissing)
        assertEquals("(deleted role)", overview.levelRewards[1].roleName)
    }
}
