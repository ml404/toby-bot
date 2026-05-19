package bot.toby.leveling

import bot.toby.notify.ChannelMentions
import bot.toby.notify.NotificationRouter
import common.events.LevelUpEvent
import common.leveling.LevelCurve
import common.notification.ChannelRouteKey
import common.notification.NotificationChannelKind
import database.dto.LevelRoleRewardDto
import database.dto.TitleDto
import database.service.LevelRoleRewardService
import database.service.TitleService
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LevelUpListenerTest {

    private lateinit var jda: JDA
    private lateinit var router: NotificationRouter
    private lateinit var levelRoleRewardService: LevelRoleRewardService
    private lateinit var titleService: TitleService
    private lateinit var listener: LevelUpListener

    private val guildId = 7L
    private val discordId = 100L

    @BeforeEach
    fun setUp() {
        jda = mockk(relaxed = true)
        router = mockk(relaxed = true)
        levelRoleRewardService = mockk(relaxed = true)
        titleService = mockk(relaxed = true)
        listener = LevelUpListener(jda, levelRoleRewardService, titleService, router)
    }

    private fun guildOnly(): Guild {
        val guild = mockk<Guild>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.idLong } returns guildId
        every { guild.id } returns guildId.toString()
        every { levelRoleRewardService.listInRange(any(), any(), any()) } returns emptyList()
        every { titleService.listAll() } returns emptyList()
        return guild
    }

    private fun captureAnnouncementEmbed(): CapturingSlot<() -> MessageCreateData> {
        val builder = slot<() -> MessageCreateData>()
        every {
            router.sendChannel(any(), any(), any(), capture(builder), any(), any())
        } just runs
        return builder
    }

    @Test
    fun `onLevelUp routes the announce embed via LEVEL_UP with the origin channel`() {
        guildOnly()
        val builder = captureAnnouncementEmbed()

        listener.onLevelUp(
            LevelUpEvent(
                discordId = discordId, guildId = guildId,
                oldLevel = 0, newLevel = 1, totalXp = 110L, channelId = 99L
            )
        )

        verify(exactly = 1) {
            router.sendChannel(
                guildId = guildId,
                route = ChannelRouteKey.LEVEL_UP,
                originChannelId = 99L,
                message = any(),
                onSent = null,
                mentions = ChannelMentions(
                    kind = NotificationChannelKind.LEVEL_UP,
                    userIds = listOf(discordId),
                ),
            )
        }
        val embed = builder.captured.invoke().embeds.single()
        assert(embed.title!!.contains("LVL 1"))
        assert(embed.description!!.contains("<@$discordId>"))
        assert(embed.description!!.contains("Level 1"))
    }

    @Test
    fun `onLevelUp passes null originChannelId when the event has no channel context`() {
        guildOnly()
        captureAnnouncementEmbed()

        listener.onLevelUp(
            LevelUpEvent(discordId, guildId, oldLevel = 0, newLevel = 1, totalXp = 110L, channelId = null)
        )

        verify(exactly = 1) {
            router.sendChannel(
                guildId = guildId,
                route = ChannelRouteKey.LEVEL_UP,
                originChannelId = null,
                message = any(),
                onSent = null,
                mentions = any(),
            )
        }
    }

    @Test
    fun `onLevelUp dispatches a LEVEL_UP DM via the router (gating handled in router)`() {
        guildOnly()
        captureAnnouncementEmbed()

        listener.onLevelUp(
            LevelUpEvent(discordId, guildId, oldLevel = 0, newLevel = 1, totalXp = 110L, channelId = 99L)
        )

        verify(exactly = 1) {
            router.sendDm(
                discordId = discordId,
                guildId = guildId,
                kind = NotificationChannelKind.LEVEL_UP,
                message = any(),
            )
        }
    }

    @Test
    fun `onLevelUp assigns every role in the level range`() {
        val guild = guildOnly()
        val role1 = mockk<Role>(relaxed = true)
        val role2 = mockk<Role>(relaxed = true)
        val addRoleAction = mockk<AuditableRestAction<Void>>(relaxed = true)

        every { levelRoleRewardService.listInRange(guildId, fromExclusive = 1, toInclusive = 3) } returns listOf(
            LevelRoleRewardDto(guildId = guildId, level = 2, roleId = 201L),
            LevelRoleRewardDto(guildId = guildId, level = 3, roleId = 202L)
        )
        every { guild.getRoleById(201L) } returns role1
        every { guild.getRoleById(202L) } returns role2
        every { role1.name } returns "Regular"
        every { role2.name } returns "Veteran"
        every { guild.addRoleToMember(any<UserSnowflake>(), any()) } returns addRoleAction

        listener.onLevelUp(
            LevelUpEvent(discordId, guildId, oldLevel = 1, newLevel = 3, totalXp = 500L, channelId = 99L)
        )

        verify(exactly = 1) { guild.addRoleToMember(any<UserSnowflake>(), role1) }
        verify(exactly = 1) { guild.addRoleToMember(any<UserSnowflake>(), role2) }
    }

    @Test
    fun `onLevelUp unlocks titles whose requiredLevel falls in the range`() {
        guildOnly()
        val gated = TitleDto(id = 10L, label = "Gated", cost = 0L).apply { requiredLevel = 2 }
        val higher = TitleDto(id = 11L, label = "Higher", cost = 0L).apply { requiredLevel = 5 }
        val ungated = TitleDto(id = 12L, label = "Free", cost = 0L)
        every { titleService.listAll() } returns listOf(gated, higher, ungated)
        every { titleService.owns(discordId, 10L) } returns false

        listener.onLevelUp(
            LevelUpEvent(discordId, guildId, oldLevel = 1, newLevel = 3, totalXp = 500L, channelId = 99L)
        )

        verify(exactly = 1) { titleService.recordPurchase(discordId, 10L) }
        verify(exactly = 0) { titleService.recordPurchase(discordId, 11L) }
        verify(exactly = 0) { titleService.recordPurchase(discordId, 12L) }
    }

    @Test
    fun `onLevelUp skips title unlock when the user already owns it`() {
        guildOnly()
        val gated = TitleDto(id = 10L, label = "Gated", cost = 0L).apply { requiredLevel = 2 }
        every { titleService.listAll() } returns listOf(gated)
        every { titleService.owns(discordId, 10L) } returns true

        listener.onLevelUp(
            LevelUpEvent(discordId, guildId, oldLevel = 1, newLevel = 3, totalXp = 500L, channelId = 99L)
        )

        verify(exactly = 0) { titleService.recordPurchase(any(), any()) }
    }

    @Test
    fun `onLevelUp returns silently when guild is gone from JDA`() {
        every { jda.getGuildById(guildId) } returns null

        listener.onLevelUp(
            LevelUpEvent(discordId, guildId, oldLevel = 0, newLevel = 1, totalXp = 110L, channelId = 99L)
        )

        verify(exactly = 0) { titleService.recordPurchase(any(), any()) }
        verify(exactly = 0) { router.sendChannel(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `onLevelUp posts a tier-colored embed with progress matching LevelCurve`() {
        guildOnly()
        val builder = captureAnnouncementEmbed()

        // Craft a totalXp that sits inside level 25 (Gold tier).
        val totalXp = LevelCurve.cumulativeXpForLevel(25) + 50L
        val achieved = LevelCurve.xpForNextLevel(24)

        listener.onLevelUp(
            LevelUpEvent(
                discordId = discordId, guildId = guildId,
                oldLevel = 24, newLevel = 25, totalXp = totalXp, channelId = 99L
            )
        )

        val embed = builder.captured.invoke().embeds.single()
        assert(embed.title!!.contains("LVL 25"))
        assert((embed.colorRaw and 0xFFFFFF) == 0xD4A017) {
            "expected Gold tier color 0xD4A017, got ${(embed.colorRaw and 0xFFFFFF).toString(16)}"
        }
        val progressField = embed.fields.single { it.name == "Progress" }.value!!
        val formattedAchieved = String.format("%,d", achieved)
        assert(progressField.contains("$formattedAchieved / $formattedAchieved XP")) {
            "progress field '$progressField' should contain '$formattedAchieved / $formattedAchieved XP'"
        }
        assert(progressField.contains("██████████")) {
            "progress field '$progressField' should render a full bar on level-up"
        }
        val totalField = embed.fields.single { it.name == "Total XP" }.value!!
        assert(totalField.contains(String.format("%,d", totalXp)))
        assert(embed.footer!!.text!!.contains("Gold"))
    }

    @Test
    fun `onLevelUp uses extended tier names and colors from Platinum through Legendary`() {
        guildOnly()
        val captured = mutableListOf<MessageCreateData>()
        every {
            router.sendChannel(any(), any(), any(), any(), any(), any())
        } answers {
            @Suppress("UNCHECKED_CAST")
            val build = arg<() -> MessageCreateData>(3)
            captured += build()
        }

        val cases = listOf(
            Triple(50, "Platinum", 0xE5E4E2),
            Triple(75, "Diamond", 0x5865F2),
            Triple(100, "Master", 0x9B30FF),
            Triple(150, "Mythic", 0xFF3CAC),
            Triple(200, "Legendary", 0xFFD700),
        )
        cases.forEachIndexed { i, (lvl, tier, color) ->
            val totalXp = LevelCurve.cumulativeXpForLevel(lvl)
            listener.onLevelUp(
                LevelUpEvent(
                    discordId = discordId, guildId = guildId,
                    oldLevel = lvl - 1, newLevel = lvl, totalXp = totalXp, channelId = 99L
                )
            )
            val embed = captured[i].embeds.single()
            assert(embed.footer!!.text!!.contains(tier)) {
                "level $lvl footer '${embed.footer!!.text}' should mention $tier"
            }
            assert((embed.colorRaw and 0xFFFFFF) == color) {
                "level $lvl expected color 0x${color.toString(16)}, got 0x${(embed.colorRaw and 0xFFFFFF).toString(16)}"
            }
        }
    }

    @Test
    fun `tier boundaries are off-by-one safe just below each threshold`() {
        guildOnly()
        val captured = mutableListOf<MessageCreateData>()
        every {
            router.sendChannel(any(), any(), any(), any(), any(), any())
        } answers {
            @Suppress("UNCHECKED_CAST")
            val build = arg<() -> MessageCreateData>(3)
            captured += build()
        }

        val boundaries = listOf(
            9 to "Bronze",     // 10 -> Silver
            24 to "Silver",    // 25 -> Gold
            49 to "Gold",      // 50 -> Platinum
            74 to "Platinum",  // 75 -> Diamond
            99 to "Diamond",   // 100 -> Master
            149 to "Master",   // 150 -> Mythic
            199 to "Mythic",   // 200 -> Legendary
        )
        boundaries.forEachIndexed { i, (lvl, expectedTier) ->
            val totalXp = LevelCurve.cumulativeXpForLevel(lvl)
            listener.onLevelUp(
                LevelUpEvent(
                    discordId = discordId, guildId = guildId,
                    oldLevel = lvl - 1, newLevel = lvl, totalXp = totalXp, channelId = 99L
                )
            )
            val embed = captured[i].embeds.single()
            assert(embed.footer!!.text!!.contains(expectedTier)) {
                "level $lvl should stay on '$expectedTier' tier, got '${embed.footer!!.text}'"
            }
        }
    }
}
