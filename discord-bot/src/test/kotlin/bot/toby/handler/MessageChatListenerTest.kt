package bot.toby.handler

import bot.toby.emote.Emotes
import database.service.leveling.XpAwardService
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import io.mockk.junit5.MockKExtension

@ExtendWith(MockKExtension::class)
class MessageChatListenerTest {

    private val xpAwardService: XpAwardService = mockk(relaxed = true)
    private val messageActivityBuffer: database.service.activity.MessageActivityBuffer = mockk(relaxed = true)
    private val listener = spyk(MessageChatListener(xpAwardService, messageActivityBuffer))

    @Test
    fun `onMessageReceived should respond correctly to toby message`() {
        val event = mockk<MessageReceivedEvent>()
        val message = mockk<Message>()
        val author = mockk<User>()
        val channel = mockk<MessageChannelUnion>()
        val guild = mockk<Guild>()
        val member = mockk<Member>()

        every { event.message } returns message
        every { event.author } returns author
        every { event.channel } returns channel
        every { event.guild } returns guild
        every { event.member } returns member
        every { author.isBot } returns false
        every { event.isWebhookMessage } returns false
        every { event.isFromGuild } returns true
        every { guild.idLong } returns 1L
        every { author.idLong } returns 2L
        every { channel.idLong } returns 3L
        every { message.contentRaw } returns "toby"
        every { member.effectiveName } returns "Matt"

        val tobyEmote = mockk<RichCustomEmoji> {
            every { asMention } returns "<:toby:123456789>"
        }
        every { guild.jda.getEmojiById(Emotes.TOBY) } returns tobyEmote

        every { channel.sendMessageFormat(any(), any(), any()).queue() } returns mockk()
        every { message.addReaction(tobyEmote).queue() } returns mockk()

        listener.onMessageReceived(event)

        verify {
            channel.sendMessageFormat(any(), any(), any())
            message.addReaction(tobyEmote)
        }
    }

    @Test
    fun `onMessageReceived should respond correctly to sigh message`() {
        val event = mockk<MessageReceivedEvent>()
        val message = mockk<Message>()
        val author = mockk<User>()
        val channel = mockk<MessageChannelUnion>()
        val guild = mockk<Guild>()
        val member = mockk<Member>()

        every { event.message } returns message
        every { event.author } returns author
        every { event.channel } returns channel
        every { event.guild } returns guild
        every { event.member } returns member
        every { author.isBot } returns false
        every { event.isWebhookMessage } returns false
        every { event.isFromGuild } returns true
        every { guild.idLong } returns 1L
        every { author.idLong } returns 2L
        every { channel.idLong } returns 3L
        every { message.contentRaw } returns "sigh"
        every { member.effectiveName } returns "Matt"

        val jessEmote = mockk<RichCustomEmoji> {
            every { asMention } returns "<:jess:987654321>"
        }
        every { guild.jda.getEmojiById(Emotes.JESS) } returns jessEmote

        every { channel.sendMessageFormat("Hey %s, what's up champ?", "Matt", jessEmote).queue() } returns mockk()

        listener.onMessageReceived(event)

        verify {
            channel.sendMessageFormat("Hey %s, what's up champ?", "Matt", any())
        }
    }

    @Test
    fun `onMessageReceived should respond correctly to yeah message`() {
        val event = mockk<MessageReceivedEvent>()
        val message = mockk<Message>()
        val author = mockk<User>()
        val channel = mockk<MessageChannelUnion>()
        val guild = mockk<Guild>()
        val member = mockk<Member>()

        every { event.message } returns message
        every { event.author } returns author
        every { event.channel } returns channel
        every { event.guild } returns guild
        every { event.member } returns member
        every { author.isBot } returns false
        every { event.isWebhookMessage } returns false
        every { event.isFromGuild } returns true
        every { guild.idLong } returns 1L
        every { author.idLong } returns 2L
        every { channel.idLong } returns 3L
        every { message.contentRaw } returns "yeah"

        every { channel.sendMessage("YEAH????").queue() } returns mockk()

        listener.onMessageReceived(event)

        verify {
            channel.sendMessage("YEAH????")
        }
    }
}
