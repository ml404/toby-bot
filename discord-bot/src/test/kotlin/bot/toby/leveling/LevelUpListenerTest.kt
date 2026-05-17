package bot.toby.leveling

import common.events.LevelUpEvent
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
    fun `onLevelUp posts announcement in the origin channel when present`() {
        val guild = mockk<Guild>(relaxed = true)
        val channel = mockk<TextChannel>(relaxed = true)
        val createAction = mockk<MessageCreateAction>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.idLong } returns guildId
        every { guild.id } returns guildId.toString()
        every { guild.getTextChannelById(99L) } returns channel
        every { channel.name } returns "general"
        every { channel.sendMessage(any<String>()) } returns createAction
        every { configService.getConfigByName(any(), any()) } returns null
        every { levelRoleRewardService.listInRange(any(), any(), any()) } returns emptyList()
        every { titleService.listAll() } returns emptyList()

        listener.onLevelUp(
            LevelUpEvent(
                discordId = discordId, guildId = guildId,
                oldLevel = 0, newLevel = 1, channelId = 99L
            )
        )

        val sent = slot<String>()
        verify { channel.sendMessage(capture(sent)) }
        assert(sent.captured.contains("Level 1"))
        assert(sent.captured.contains("<@$discordId>"))
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
        every { systemChannel.sendMessage(any<String>()) } returns createAction
        every { configService.getConfigByName(any(), any()) } returns null
        every { levelRoleRewardService.listInRange(any(), any(), any()) } returns emptyList()
        every { titleService.listAll() } returns emptyList()

        listener.onLevelUp(
            LevelUpEvent(discordId, guildId, oldLevel = 0, newLevel = 1, channelId = null)
        )

        verify { systemChannel.sendMessage(any<String>()) }
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
        every { channel.sendMessage(any<String>()) } returns createAction
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
            LevelUpEvent(discordId, guildId, oldLevel = 1, newLevel = 3, channelId = 99L)
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
        every { channel.sendMessage(any<String>()) } returns createAction
        every { configService.getConfigByName(any(), any()) } returns null
        every { levelRoleRewardService.listInRange(any(), any(), any()) } returns emptyList()

        val gated = TitleDto(id = 10L, label = "Gated", cost = 0L).apply { requiredLevel = 2 }
        val higher = TitleDto(id = 11L, label = "Higher", cost = 0L).apply { requiredLevel = 5 }
        val ungated = TitleDto(id = 12L, label = "Free", cost = 0L)
        every { titleService.listAll() } returns listOf(gated, higher, ungated)
        every { titleService.owns(discordId, 10L) } returns false

        listener.onLevelUp(
            LevelUpEvent(discordId, guildId, oldLevel = 1, newLevel = 3, channelId = 99L)
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
        every { channel.sendMessage(any<String>()) } returns createAction
        every { configService.getConfigByName(any(), any()) } returns null
        every { levelRoleRewardService.listInRange(any(), any(), any()) } returns emptyList()

        val gated = TitleDto(id = 10L, label = "Gated", cost = 0L).apply { requiredLevel = 2 }
        every { titleService.listAll() } returns listOf(gated)
        every { titleService.owns(discordId, 10L) } returns true

        listener.onLevelUp(
            LevelUpEvent(discordId, guildId, oldLevel = 1, newLevel = 3, channelId = 99L)
        )

        verify(exactly = 0) { titleService.recordPurchase(any(), any()) }
    }

    @Test
    fun `onLevelUp returns silently when guild is gone from JDA`() {
        every { jda.getGuildById(guildId) } returns null

        listener.onLevelUp(
            LevelUpEvent(discordId, guildId, oldLevel = 0, newLevel = 1, channelId = 99L)
        )

        verify(exactly = 0) { titleService.recordPurchase(any(), any()) }
    }
}
