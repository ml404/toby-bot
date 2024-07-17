import io.mockk.every
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.Logger
import toby.emote.Emotes
import toby.handler.Handler
import toby.jpa.service.*

@ExtendWith(MockKExtension::class)
class HandlerTest {

    // Mock dependencies
    private val configService: IConfigService = mockk()
    private val userService: IUserService = mockk()
    private val brotherService: IBrotherService = mockk()
    private val musicFileService: IMusicFileService = mockk()
    private val excuseService: IExcuseService = mockk()
    private val handler = Handler(configService, brotherService, userService, musicFileService, excuseService)

    @Test
    fun `onMessageReceived should respond correctly to different messages`() {
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
        every { message.contentRaw } returns "toby"
        every { member.effectiveName } returns "Matt"

        val tobyEmote = mockk<RichCustomEmoji> {
            every { asMention } returns "<:toby:123456789>"
        }
        every { guild.jda.getEmojiById(Emotes.TOBY) } returns tobyEmote

        every { channel.sendMessageFormat(any(), any(), any()).queue() } returns mockk()
        every { channel.sendMessage(any<CharSequence>()) } returns mockk()
        every { message.addReaction(tobyEmote).queue() } returns mockk()

        handler.onMessageReceived(event)

        verify {
            channel.sendMessageFormat(any(), any(), any())
            message.addReaction(tobyEmote)
        }
    }
}
