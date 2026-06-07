package bot.toby.install

import database.dto.guild.ConfigDto
import database.dto.guild.ConfigDto.Configurations
import database.service.activity.InstallEventService
import database.service.guild.ConfigService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.SelfMember
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.requests.restaction.CacheRestAction
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class InstallWelcomeHandlerTest {

    private lateinit var configService: ConfigService
    private lateinit var installEventService: InstallEventService
    private lateinit var handler: InstallWelcomeHandler
    private lateinit var event: GuildJoinEvent
    private lateinit var guild: Guild
    private lateinit var selfMember: SelfMember
    private lateinit var systemChannel: TextChannel
    private lateinit var fallbackChannel: TextChannel
    private lateinit var messageAction: MessageCreateAction

    @BeforeEach
    fun setUp() {
        configService = mockk(relaxed = true)
        installEventService = mockk(relaxed = true)
        handler = InstallWelcomeHandler(configService, installEventService)

        event = mockk(relaxed = true)
        guild = mockk(relaxed = true)
        selfMember = mockk(relaxed = true)
        systemChannel = mockk(relaxed = true)
        fallbackChannel = mockk(relaxed = true)
        messageAction = mockk(relaxed = true)

        every { event.guild } returns guild
        every { guild.id } returns "100"
        every { guild.idLong } returns 100L
        every { guild.name } returns "Test Guild"
        every { guild.selfMember } returns selfMember
        every { systemChannel.name } returns "general"
        every { fallbackChannel.name } returns "fallback"

        every { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) } returns messageAction
        every { fallbackChannel.sendMessageEmbeds(any<MessageEmbed>()) } returns messageAction
        every { messageAction.addComponents(any<ActionRow>()) } returns messageAction
        every { messageAction.queue() } just runs
    }

    @Test
    fun `records the JOIN event even when the welcome is skipped (already installed)`() {
        // The ledger captures every lifecycle transition independently of
        // the welcome message — re-invites still count as a JOIN.
        every { configService.getConfigByName(Configurations.INSTALL_MODE.configValue, "100") } returns
            ConfigDto(Configurations.INSTALL_MODE.configValue, "express", "100")

        handler.onGuildJoin(event)

        verify(exactly = 1) { installEventService.recordJoin(100L, any()) }
    }

    @Test
    fun `records the JOIN event when posting a fresh welcome`() {
        every { configService.getConfigByName(any(), any()) } returns null
        every { guild.systemChannel } returns systemChannel
        every {
            selfMember.hasPermission(systemChannel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)
        } returns true

        handler.onGuildJoin(event)

        verify(exactly = 1) { installEventService.recordJoin(100L, any()) }
        verify(exactly = 1) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `skips welcome when INSTALL_MODE is already set to express`() {
        every { configService.getConfigByName(Configurations.INSTALL_MODE.configValue, "100") } returns
            ConfigDto(Configurations.INSTALL_MODE.configValue, "express", "100")

        handler.onGuildJoin(event)

        verify(exactly = 1) {
            configService.getConfigByName(Configurations.INSTALL_MODE.configValue, "100")
        }
        verify(exactly = 0) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 0) { fallbackChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `skips welcome when INSTALL_MODE is already set to custom`() {
        every { configService.getConfigByName(Configurations.INSTALL_MODE.configValue, "100") } returns
            ConfigDto(Configurations.INSTALL_MODE.configValue, "custom", "100")

        handler.onGuildJoin(event)

        verify(exactly = 0) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `posts the welcome-back variant when INSTALLED_AT survives a re-invite`() {
        // INSTALL_MODE was cleared on the previous leave, but INSTALLED_AT
        // survives — so this is a returning server and should get the
        // welcome-back greeting, not the first-time pitch.
        every { configService.getConfigByName(Configurations.INSTALL_MODE.configValue, "100") } returns null
        every { configService.getConfigByName(Configurations.INSTALLED_AT.configValue, "100") } returns
            ConfigDto(Configurations.INSTALLED_AT.configValue, "1700000000000", "100")
        every { guild.systemChannel } returns systemChannel
        every {
            selfMember.hasPermission(systemChannel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)
        } returns true

        val embedSlot = slot<MessageEmbed>()
        every { systemChannel.sendMessageEmbeds(capture(embedSlot)) } returns messageAction

        handler.onGuildJoin(event)

        verify(exactly = 1) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
        assertTrue(embedSlot.captured.title!!.contains("Welcome back"))
    }

    @Test
    fun `posts the first-time welcome when there is no prior INSTALLED_AT`() {
        every { configService.getConfigByName(any(), any()) } returns null
        every { guild.systemChannel } returns systemChannel
        every {
            selfMember.hasPermission(systemChannel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)
        } returns true

        val embedSlot = slot<MessageEmbed>()
        every { systemChannel.sendMessageEmbeds(capture(embedSlot)) } returns messageAction

        handler.onGuildJoin(event)

        assertTrue(embedSlot.captured.title!!.contains("Thanks for adding me"))
    }

    @Test
    fun `posts to system channel when writable`() {
        every { configService.getConfigByName(any(), any()) } returns null
        every { guild.systemChannel } returns systemChannel
        every {
            selfMember.hasPermission(systemChannel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)
        } returns true

        handler.onGuildJoin(event)

        verify(exactly = 1) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { messageAction.addComponents(any<ActionRow>()) }
    }

    @Test
    fun `falls back to first writable text channel when system channel not writable`() {
        every { configService.getConfigByName(any(), any()) } returns null
        every { guild.systemChannel } returns systemChannel
        every {
            selfMember.hasPermission(systemChannel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)
        } returns false
        every { guild.textChannels } returns listOf(fallbackChannel)
        every {
            selfMember.hasPermission(fallbackChannel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)
        } returns true

        handler.onGuildJoin(event)

        verify(exactly = 0) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { fallbackChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `falls back to first writable text channel when system channel is null`() {
        every { configService.getConfigByName(any(), any()) } returns null
        every { guild.systemChannel } returns null
        every { guild.textChannels } returns listOf(fallbackChannel)
        every {
            selfMember.hasPermission(fallbackChannel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)
        } returns true

        handler.onGuildJoin(event)

        verify(exactly = 1) { fallbackChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `dm fallback when no writable channel exists and guild owner resolvable`() {
        every { configService.getConfigByName(any(), any()) } returns null
        every { guild.systemChannel } returns null
        every { guild.textChannels } returns listOf(fallbackChannel)
        every {
            selfMember.hasPermission(fallbackChannel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)
        } returns false

        val owner = mockk<Member>(relaxed = true)
        val ownerUser = mockk<User>(relaxed = true)
        val privateChannel = mockk<PrivateChannel>(relaxed = true)

        @Suppress("UNCHECKED_CAST")
        val openDmAction = mockk<CacheRestAction<PrivateChannel>>(relaxed = true)

        @Suppress("UNCHECKED_CAST")
        val sendAction = mockk<MessageCreateAction>(relaxed = true)

        every { guild.owner } returns owner
        every { owner.user } returns ownerUser
        every { ownerUser.openPrivateChannel() } returns openDmAction
        every { openDmAction.queue(any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (firstArg() as java.util.function.Consumer<PrivateChannel>).accept(privateChannel)
        }
        every { privateChannel.sendMessageEmbeds(any<MessageEmbed>()) } returns sendAction
        every { sendAction.queue(any<java.util.function.Consumer<Message>>(), any()) } answers {}

        handler.onGuildJoin(event)

        verify(exactly = 0) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 0) { fallbackChannel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { ownerUser.openPrivateChannel() }
        verify(exactly = 1) { privateChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `silent no-op when no writable channel exists and no resolvable owner`() {
        every { configService.getConfigByName(any(), any()) } returns null
        every { guild.systemChannel } returns null
        every { guild.textChannels } returns emptyList()
        every { guild.owner } returns null

        handler.onGuildJoin(event)

        verify(exactly = 0) { systemChannel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 0) { fallbackChannel.sendMessageEmbeds(any<MessageEmbed>()) }
    }
}
