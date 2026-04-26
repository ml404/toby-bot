package bot.toby.notify

import database.duel.PendingDuelRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import web.event.WebDuelOfferedEvent
import java.time.Duration

class WebDuelOfferNotifierTest {

    private lateinit var jda: JDA
    private lateinit var pendingDuelRegistry: PendingDuelRegistry
    private lateinit var guild: Guild
    private lateinit var channel: TextChannel
    private lateinit var createAction: MessageCreateAction
    private lateinit var notifier: WebDuelOfferNotifier

    private val guildId = 42L
    private val duelId = 99L
    private val initiatorId = 100L
    private val opponentId = 200L

    private fun event(): WebDuelOfferedEvent = WebDuelOfferedEvent(
        guildId = guildId,
        duelId = duelId,
        initiatorDiscordId = initiatorId,
        opponentDiscordId = opponentId,
        stake = 50L
    )

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        pendingDuelRegistry = mockk(relaxed = true)
        every { pendingDuelRegistry.ttl } returns Duration.ofMinutes(3)
        guild = mockk(relaxed = true)
        channel = mockk(relaxed = true)
        createAction = mockk(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.systemChannel } returns channel
        every { guild.selfMember.hasPermission(channel, *anyVararg<Permission>()) } returns true
        every { channel.sendMessageEmbeds(any<MessageEmbed>()) } returns createAction
        every { createAction.addContent(any()) } returns createAction
        notifier = WebDuelOfferNotifier(jda, pendingDuelRegistry)
    }

    @Test
    fun `happy path posts the embed and chains addContent for the opponent ping`() {
        notifier.on(event())

        verify(exactly = 1) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { createAction.addContent("<@$opponentId>") }
    }

    @Test
    fun `skips when bot is not in the guild`() {
        every { jda.getGuildById(guildId) } returns null

        notifier.on(event())

        verify(exactly = 0) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `skips when guild has no system channel`() {
        every { guild.systemChannel } returns null

        notifier.on(event())

        verify(exactly = 0) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `skips when bot lacks permission on the system channel`() {
        every { guild.selfMember.hasPermission(channel, *anyVararg<Permission>()) } returns false

        notifier.on(event())

        verify(exactly = 0) { channel.sendMessageEmbeds(any<MessageEmbed>()) }
    }
}
