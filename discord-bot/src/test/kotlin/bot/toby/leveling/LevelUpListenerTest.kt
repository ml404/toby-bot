package bot.toby.leveling

import common.events.LevelUpEvent
import common.leveling.LevelCurve
import database.dto.LevelRoleRewardDto
import database.dto.TitleDto
import database.service.ConfigService
import database.service.LevelRoleRewardService
import database.service.TitleService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LevelUpListenerTest {

    private lateinit var jda: JDA
    private lateinit var configService: ConfigService
    private lateinit var levelRoleRewardService: LevelRoleRewardService
    private lateinit var titleService: TitleService
    private lateinit var listener: LevelUpListener

    private val guildId = 7L
    private val discordId = 100L

    @BeforeEach
    fun setUp() {
        jda = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        levelRoleRewardService = mockk(relaxed = true)
        titleService = mockk(relaxed = true)
        listener = LevelUpListener(jda, configService, levelRoleRewardService, titleService)
    }

    @Test
    fun `onLevelUp posts an embed in the origin channel when present`() {
        val guild = mockk<Guild>(relaxed = true)
        val channel = mockk<TextChannel>(relaxed = true)
        val createAction = mockk<MessageCreateAction>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.idLong } returns guildId
        every { guild.id } returns guildId.toString()
        every { guild.getTextChannelById(99L) } returns channel
        every { channel.name } returns "general"
        every { channel.sendMessageEmbeds(any<MessageEmbed>()) } returns createAction
        every { configService.getConfigByName(any(), any()) } returns null
        every { levelRoleRewardService.listInRange(any(), any(), any()) } returns emptyList()
        every { titleService.listAll() } returns emptyList()

        listener.onLevelUp(
            LevelUpEvent(
                discordId = discordId, guildId = guildId,
                oldLevel = 0, newLevel = 1, totalXp = 110L, channelId = 99L
            )
        )

        val sent = slot<MessageEmbed>()
        verify { channel.sendMessageEmbeds(capture(sent)) }
        val embed = sent.captured
        assert(embed.title!!.contains("LVL 1"))
        assert(embed.description!!.contains("<@$discordId>"))
        assert(embed.description!!.contains("Level 1"))
    }

    @Test
    fun `onLevelUp falls back to system channel when no origin channel`() {
        val guild = mockk<Guild>(relaxed = true)
        val systemChannel = mockk<TextChannel>(relaxed = true)
        val createAction = mockk<MessageCreateAction>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.idLong } returns guildId
        every { guild.id } returns guildId.toString()
        every { guild.systemChannel } returns systemChannel
        every { systemChannel.name } returns "general"
        every { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) } returns createAction
        every { configService.getConfigByName(any(), any()) } returns null
        every { levelRoleRewardService.listInRange(any(), any(), any()) } returns emptyList()
        every { titleService.listAll() } returns emptyList()

        listener.onLevelUp(
            LevelUpEvent(discordId, guildId, oldLevel = 0, newLevel = 1, totalXp = 110L, channelId = null)
        )

        verify { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `onLevelUp assigns every role in the level range`() {
        val guild = mockk<Guild>(relaxed = true)
        val channel = mockk<TextChannel>(relaxed = true)
        val createAction = mockk<MessageCreateAction>(relaxed = true)
        val role1 = mockk<Role>(relaxed = true)
        val role2 = mockk<Role>(relaxed = true)
        val addRoleAction = mockk<AuditableRestAction<Void>>(relaxed = true)

        every { jda.getGuildById(guildId) } returns guild
        every { guild.idLong } returns guildId
        every { guild.id } returns guildId.toString()
        every { guild.getTextChannelById(any<Long>()) } returns channel
        every { channel.sendMessageEmbeds(any<MessageEmbed>()) } returns createAction
        every { configService.getConfigByName(any(), any()) } returns null
        every { titleService.listAll() } returns emptyList()
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
        val guild = mockk<Guild>(relaxed = true)
        val channel = mockk<TextChannel>(relaxed = true)
        val createAction = mockk<MessageCreateAction>(relaxed = true)

        every { jda.getGuildById(guildId) } returns guild
        every { guild.idLong } returns guildId
        every { guild.id } returns guildId.toString()
        every { guild.getTextChannelById(any<Long>()) } returns channel
        every { channel.sendMessageEmbeds(any<MessageEmbed>()) } returns createAction
        every { configService.getConfigByName(any(), any()) } returns null
        every { levelRoleRewardService.listInRange(any(), any(), any()) } returns emptyList()

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
        val guild = mockk<Guild>(relaxed = true)
        val channel = mockk<TextChannel>(relaxed = true)
        val createAction = mockk<MessageCreateAction>(relaxed = true)

        every { jda.getGuildById(guildId) } returns guild
        every { guild.idLong } returns guildId
        every { guild.id } returns guildId.toString()
        every { guild.getTextChannelById(any<Long>()) } returns channel
        every { channel.sendMessageEmbeds(any<MessageEmbed>()) } returns createAction
        every { configService.getConfigByName(any(), any()) } returns null
        every { levelRoleRewardService.listInRange(any(), any(), any()) } returns emptyList()

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
    }

    @Test
    fun `onLevelUp posts a tier-colored embed with progress matching LevelCurve`() {
        val guild = mockk<Guild>(relaxed = true)
        val channel = mockk<TextChannel>(relaxed = true)
        val createAction = mockk<MessageCreateAction>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.idLong } returns guildId
        every { guild.id } returns guildId.toString()
        every { guild.getTextChannelById(99L) } returns channel
        every { channel.name } returns "general"
        every { channel.sendMessageEmbeds(any<MessageEmbed>()) } returns createAction
        every { configService.getConfigByName(any(), any()) } returns null
        every { levelRoleRewardService.listInRange(any(), any(), any()) } returns emptyList()
        every { titleService.listAll() } returns emptyList()

        // Craft a totalXp that sits inside level 25 (Gold tier).
        val totalXp = LevelCurve.cumulativeXpForLevel(25) + 50L
        val expected = LevelCurve.progress(totalXp)

        listener.onLevelUp(
            LevelUpEvent(
                discordId = discordId, guildId = guildId,
                oldLevel = 24, newLevel = 25, totalXp = totalXp, channelId = 99L
            )
        )

        val sent = slot<MessageEmbed>()
        verify { channel.sendMessageEmbeds(capture(sent)) }
        val embed = sent.captured
        assert(embed.title!!.contains("LVL 25"))
        assert((embed.colorRaw and 0xFFFFFF) == 0xD4A017) {
            "expected Gold tier color 0xD4A017, got ${(embed.colorRaw and 0xFFFFFF).toString(16)}"
        }
        val progressField = embed.fields.single { it.name == "Progress" }.value!!
        val formattedInto = String.format("%,d", expected.xpIntoLevel)
        val formattedNext = String.format("%,d", expected.xpForNextLevel)
        assert(progressField.contains("$formattedInto / $formattedNext XP")) {
            "progress field '$progressField' should contain '$formattedInto / $formattedNext XP'"
        }
        val totalField = embed.fields.single { it.name == "Total XP" }.value!!
        assert(totalField.contains(String.format("%,d", totalXp)))
        assert(embed.footer!!.text!!.contains("Gold"))
    }
}
