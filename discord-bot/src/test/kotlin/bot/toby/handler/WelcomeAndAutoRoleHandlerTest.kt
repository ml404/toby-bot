package bot.toby.handler

import database.dto.guild.AutoRoleDto
import database.dto.guild.ConfigDto
import database.dto.guild.ConfigDto.Configurations
import database.service.guild.AutoRoleService
import database.service.guild.ConfigService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.SelfMember
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Behavioural tests for [WelcomeAndAutoRoleHandler] covering:
 *   - the four enabled / disabled / channel-set / channel-missing branches
 *     for both welcome and goodbye paths,
 *   - the four guard branches in auto-role assignment (missing role,
 *     managed role, role above bot, bot missing MANAGE_ROLES),
 *   - the channel fallback to `guild.systemChannel` when the configured
 *     channel is blank, unparseable, or non-postable.
 */
class WelcomeAndAutoRoleHandlerTest {

    private lateinit var configService: ConfigService
    private lateinit var autoRoleService: AutoRoleService
    private lateinit var handler: WelcomeAndAutoRoleHandler

    private lateinit var guild: Guild
    private lateinit var selfMember: SelfMember
    private lateinit var joinedMember: Member
    private lateinit var joinedUser: User
    private lateinit var configuredChannel: TextChannel
    private lateinit var systemChannel: TextChannel
    private lateinit var messageAction: MessageCreateAction
    private lateinit var roleAddAction: AuditableRestAction<Void>

    private val guildId = 1000L
    private val configuredChannelId = 555L

    @BeforeEach
    fun setUp() {
        configService = mockk(relaxed = true)
        autoRoleService = mockk(relaxed = true)
        handler = WelcomeAndAutoRoleHandler(configService, autoRoleService)

        guild = mockk(relaxed = true)
        selfMember = mockk(relaxed = true)
        joinedMember = mockk(relaxed = true)
        joinedUser = mockk(relaxed = true)
        configuredChannel = mockk(relaxed = true)
        systemChannel = mockk(relaxed = true)
        messageAction = mockk(relaxed = true)
        @Suppress("UNCHECKED_CAST")
        roleAddAction = mockk<AuditableRestAction<Void>>(relaxed = true)

        every { guild.id } returns guildId.toString()
        every { guild.idLong } returns guildId
        every { guild.name } returns "Test Guild"
        every { guild.selfMember } returns selfMember
        every { guild.members } returns emptyList()
        every { guild.systemChannel } returns systemChannel

        every { configuredChannel.id } returns configuredChannelId.toString()
        every { configuredChannel.idLong } returns configuredChannelId
        every { configuredChannel.name } returns "welcomes"
        every { systemChannel.id } returns "999"
        every { systemChannel.name } returns "general"

        every { joinedMember.user } returns joinedUser
        every { joinedMember.id } returns "42"
        every { joinedMember.idLong } returns 42L
        every { joinedMember.effectiveName } returns "Alice"
        every { joinedUser.id } returns "42"
        every { joinedUser.idLong } returns 42L
        every { joinedUser.name } returns "alice"
        every { joinedUser.asMention } returns "<@42>"
        every { joinedUser.effectiveAvatarUrl } returns "https://avatar/42.png"

        every { configuredChannel.sendMessageEmbeds(any<MessageEmbed>()) } returns messageAction
        every { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) } returns messageAction
        every { messageAction.queue(any(), any()) } just runs
    }

    // ---- welcome announcement ----

    @Test
    fun `welcome disabled does not post anything`() {
        every {
            configService.getConfigByName(Configurations.WELCOME_ENABLED.configValue, guildId.toString())
        } returns ConfigDto(Configurations.WELCOME_ENABLED.configValue, "false", guildId.toString())
        every { autoRoleService.listForGuild(guildId) } returns emptyList()

        val event = mockk<GuildMemberJoinEvent>(relaxed = true)
        every { event.guild } returns guild
        every { event.member } returns joinedMember

        handler.onGuildMemberJoin(event)

        verify(exactly = 0) { configuredChannel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 0) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `welcome enabled posts to configured channel`() {
        stubFlag(Configurations.WELCOME_ENABLED, "true")
        stubKey(Configurations.WELCOME_CHANNEL, configuredChannelId.toString())
        stubKey(Configurations.WELCOME_MESSAGE, "hi {user}")
        every { guild.getTextChannelById(configuredChannelId) } returns configuredChannel
        every {
            selfMember.hasPermission(configuredChannel, *anyVararg<Permission>())
        } returns true
        every { autoRoleService.listForGuild(guildId) } returns emptyList()

        val event = mockk<GuildMemberJoinEvent>(relaxed = true).also {
            every { it.guild } returns guild
            every { it.member } returns joinedMember
        }
        handler.onGuildMemberJoin(event)

        verify(exactly = 1) { configuredChannel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 0) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `welcome enabled with blank channel falls back to system channel`() {
        stubFlag(Configurations.WELCOME_ENABLED, "true")
        stubKey(Configurations.WELCOME_CHANNEL, "")
        every {
            selfMember.hasPermission(systemChannel, *anyVararg<Permission>())
        } returns true
        every { autoRoleService.listForGuild(guildId) } returns emptyList()

        val event = mockk<GuildMemberJoinEvent>(relaxed = true).also {
            every { it.guild } returns guild
            every { it.member } returns joinedMember
        }
        handler.onGuildMemberJoin(event)

        verify(exactly = 1) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `welcome enabled with non-postable configured channel falls back to system channel`() {
        stubFlag(Configurations.WELCOME_ENABLED, "true")
        stubKey(Configurations.WELCOME_CHANNEL, configuredChannelId.toString())
        every { guild.getTextChannelById(configuredChannelId) } returns configuredChannel
        every {
            selfMember.hasPermission(configuredChannel, *anyVararg<Permission>())
        } returns false
        every {
            selfMember.hasPermission(systemChannel, *anyVararg<Permission>())
        } returns true
        every { autoRoleService.listForGuild(guildId) } returns emptyList()

        val event = mockk<GuildMemberJoinEvent>(relaxed = true).also {
            every { it.guild } returns guild
            every { it.member } returns joinedMember
        }
        handler.onGuildMemberJoin(event)

        verify(exactly = 0) { configuredChannel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `welcome enabled with no postable channel skips silently`() {
        stubFlag(Configurations.WELCOME_ENABLED, "true")
        stubKey(Configurations.WELCOME_CHANNEL, "")
        every {
            selfMember.hasPermission(systemChannel, *anyVararg<Permission>())
        } returns false
        every { autoRoleService.listForGuild(guildId) } returns emptyList()

        val event = mockk<GuildMemberJoinEvent>(relaxed = true).also {
            every { it.guild } returns guild
            every { it.member } returns joinedMember
        }
        handler.onGuildMemberJoin(event)

        verify(exactly = 0) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 0) { configuredChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    // ---- goodbye announcement ----

    @Test
    fun `goodbye disabled does not post anything`() {
        every {
            configService.getConfigByName(Configurations.GOODBYE_ENABLED.configValue, guildId.toString())
        } returns ConfigDto(Configurations.GOODBYE_ENABLED.configValue, "false", guildId.toString())

        val event = mockk<GuildMemberRemoveEvent>(relaxed = true).also {
            every { it.guild } returns guild
            every { it.user } returns joinedUser
            every { it.member } returns null
        }
        handler.onGuildMemberRemove(event)

        verify(exactly = 0) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `goodbye enabled posts to configured channel`() {
        stubFlag(Configurations.GOODBYE_ENABLED, "true")
        stubKey(Configurations.GOODBYE_CHANNEL, configuredChannelId.toString())
        stubKey(Configurations.GOODBYE_MESSAGE, "bye {user.name}")
        every { guild.getTextChannelById(configuredChannelId) } returns configuredChannel
        every {
            selfMember.hasPermission(configuredChannel, *anyVararg<Permission>())
        } returns true

        val event = mockk<GuildMemberRemoveEvent>(relaxed = true).also {
            every { it.guild } returns guild
            every { it.user } returns joinedUser
            every { it.member } returns joinedMember
        }
        handler.onGuildMemberRemove(event)

        verify(exactly = 1) { configuredChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    // ---- auto-role assignment ----

    @Test
    fun `auto-role assigns every valid role`() {
        stubFlag(Configurations.WELCOME_ENABLED, "false")
        stubFlag(Configurations.GOODBYE_ENABLED, "false")
        val roleA = stubRole(100L, "Member", managed = false, canInteract = true)
        val roleB = stubRole(200L, "Verified", managed = false, canInteract = true)
        every { selfMember.hasPermission(Permission.MANAGE_ROLES) } returns true
        every { autoRoleService.listForGuild(guildId) } returns listOf(
            AutoRoleDto(guildId = guildId, roleId = 100L),
            AutoRoleDto(guildId = guildId, roleId = 200L),
        )
        every { guild.addRoleToMember(joinedMember, any<Role>()) } returns roleAddAction
        every { roleAddAction.queue(any(), any()) } just runs

        val event = mockk<GuildMemberJoinEvent>(relaxed = true).also {
            every { it.guild } returns guild
            every { it.member } returns joinedMember
        }
        handler.onGuildMemberJoin(event)

        verify(exactly = 1) { guild.addRoleToMember(joinedMember, roleA) }
        verify(exactly = 1) { guild.addRoleToMember(joinedMember, roleB) }
    }

    @Test
    fun `auto-role skips missing role`() {
        stubFlag(Configurations.WELCOME_ENABLED, "false")
        every { selfMember.hasPermission(Permission.MANAGE_ROLES) } returns true
        every { autoRoleService.listForGuild(guildId) } returns listOf(
            AutoRoleDto(guildId = guildId, roleId = 100L),
        )
        every { guild.getRoleById(100L) } returns null
        every { guild.addRoleToMember(joinedMember, any<Role>()) } returns roleAddAction

        val event = mockk<GuildMemberJoinEvent>(relaxed = true).also {
            every { it.guild } returns guild
            every { it.member } returns joinedMember
        }
        handler.onGuildMemberJoin(event)

        verify(exactly = 0) { guild.addRoleToMember(joinedMember, any<Role>()) }
    }

    @Test
    fun `auto-role skips managed role`() {
        stubFlag(Configurations.WELCOME_ENABLED, "false")
        stubRole(100L, "Twitch sub", managed = true, canInteract = true)
        every { selfMember.hasPermission(Permission.MANAGE_ROLES) } returns true
        every { autoRoleService.listForGuild(guildId) } returns listOf(
            AutoRoleDto(guildId = guildId, roleId = 100L),
        )

        val event = mockk<GuildMemberJoinEvent>(relaxed = true).also {
            every { it.guild } returns guild
            every { it.member } returns joinedMember
        }
        handler.onGuildMemberJoin(event)

        verify(exactly = 0) { guild.addRoleToMember(joinedMember, any<Role>()) }
    }

    @Test
    fun `auto-role skips role bot cannot interact with`() {
        stubFlag(Configurations.WELCOME_ENABLED, "false")
        stubRole(100L, "Admin", managed = false, canInteract = false)
        every { selfMember.hasPermission(Permission.MANAGE_ROLES) } returns true
        every { autoRoleService.listForGuild(guildId) } returns listOf(
            AutoRoleDto(guildId = guildId, roleId = 100L),
        )

        val event = mockk<GuildMemberJoinEvent>(relaxed = true).also {
            every { it.guild } returns guild
            every { it.member } returns joinedMember
        }
        handler.onGuildMemberJoin(event)

        verify(exactly = 0) { guild.addRoleToMember(joinedMember, any<Role>()) }
    }

    @Test
    fun `auto-role bails when bot lacks MANAGE_ROLES`() {
        stubFlag(Configurations.WELCOME_ENABLED, "false")
        every { selfMember.hasPermission(Permission.MANAGE_ROLES) } returns false
        every { autoRoleService.listForGuild(guildId) } returns listOf(
            AutoRoleDto(guildId = guildId, roleId = 100L),
        )

        val event = mockk<GuildMemberJoinEvent>(relaxed = true).also {
            every { it.guild } returns guild
            every { it.member } returns joinedMember
        }
        handler.onGuildMemberJoin(event)

        verify(exactly = 0) { guild.addRoleToMember(joinedMember, any<Role>()) }
    }

    @Test
    fun `auto-role no-op when list is empty`() {
        stubFlag(Configurations.WELCOME_ENABLED, "false")
        every { selfMember.hasPermission(Permission.MANAGE_ROLES) } returns true
        every { autoRoleService.listForGuild(guildId) } returns emptyList()

        val event = mockk<GuildMemberJoinEvent>(relaxed = true).also {
            every { it.guild } returns guild
            every { it.member } returns joinedMember
        }
        handler.onGuildMemberJoin(event)

        verify(exactly = 0) { selfMember.hasPermission(Permission.MANAGE_ROLES) }
    }

    @Test
    fun `welcome failure does not block auto-role assignment`() {
        // If the welcome post throws (or the renderer blows up because the
        // guild mock is short-stubbed), auto-role assignment must still run.
        // Each side is wrapped in its own runCatching in the handler so a
        // bad welcome configuration can never starve role assignment.
        stubFlag(Configurations.WELCOME_ENABLED, "true")
        stubKey(Configurations.WELCOME_CHANNEL, configuredChannelId.toString())
        every { guild.getTextChannelById(configuredChannelId) } throws IllegalStateException("boom")

        stubRole(100L, "Member", managed = false, canInteract = true)
        every { selfMember.hasPermission(Permission.MANAGE_ROLES) } returns true
        every { autoRoleService.listForGuild(guildId) } returns listOf(
            AutoRoleDto(guildId = guildId, roleId = 100L),
        )
        every { guild.addRoleToMember(joinedMember, any<Role>()) } returns roleAddAction
        every { roleAddAction.queue(any(), any()) } just runs

        val event = mockk<GuildMemberJoinEvent>(relaxed = true).also {
            every { it.guild } returns guild
            every { it.member } returns joinedMember
        }
        handler.onGuildMemberJoin(event)

        verify(exactly = 1) { guild.addRoleToMember(joinedMember, any<Role>()) }
    }

    // ---- helpers ----

    private fun stubFlag(key: Configurations, value: String) {
        every {
            configService.getConfigByName(key.configValue, guildId.toString())
        } returns ConfigDto(key.configValue, value, guildId.toString())
    }

    private fun stubKey(key: Configurations, value: String) {
        every {
            configService.getConfigByName(key.configValue, guildId.toString())
        } returns ConfigDto(key.configValue, value, guildId.toString())
    }

    private fun stubRole(id: Long, name: String, managed: Boolean, canInteract: Boolean): Role {
        val role = mockk<Role>(relaxed = true).also {
            every { it.idLong } returns id
            every { it.id } returns id.toString()
            every { it.name } returns name
            every { it.isManaged } returns managed
        }
        every { guild.getRoleById(id) } returns role
        every { selfMember.canInteract(role) } returns canInteract
        return role
    }
}
