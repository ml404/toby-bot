package toby.command

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction
import net.dv8tion.jda.internal.requests.restaction.WebhookMessageCreateActionImpl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentMatchers.*
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.kotlin.anyVararg
import toby.jpa.dto.UserDto

interface CommandTest {
    @BeforeEach
    fun setUpCommonMocks() {
        Mockito.`when`(event.hook).thenReturn(interactionHook)
        Mockito.`when`(event.deferReply()).thenReturn(replyCallbackAction)
        Mockito.`when`(event.deferReply()).thenReturn(replyCallbackAction)
        Mockito.`when`(event.guild).thenReturn(guild)
        Mockito.`when`(event.user).thenReturn(user)
        Mockito.`when`(event.reply(anyString())).thenReturn(replyCallbackAction)
        Mockito.`when`(event.replyFormat(anyString(), any())).thenReturn(replyCallbackAction)
        Mockito.`when`(replyCallbackAction.setEphemeral(anyBoolean())).thenReturn(replyCallbackAction)
        //refers to the user making the call
        Mockito.`when`(event.member).thenReturn(member)
        Mockito.`when`(event.channel).thenReturn(messageChannelUnion)
        Mockito.`when`(user.effectiveName).thenReturn("UserName")
        Mockito.`when`(user.name).thenReturn("UserName")
        Mockito.`when`(user.isBot).thenReturn(false)
        Mockito.`when`(interactionHook.deleteOriginal()).thenReturn(restAction as RestAction<Void>)
        Mockito.`when`(interactionHook.sendMessage(anyString())).thenReturn(webhookMessageCreateAction as WebhookMessageCreateAction<Message>)
        Mockito.`when`(interactionHook.sendMessageFormat(anyString(), any(Array<Any>::class.java))).thenReturn(webhookMessageCreateAction)
        Mockito.`when`(interactionHook.retrieveOriginal()).thenReturn(restAction as RestAction<Message>)
        Mockito.`when`(interactionHook.sendMessageEmbeds(any(), anyVararg())).thenReturn(webhookMessageCreateAction)
        Mockito.`when`(webhookMessageCreateAction.addActionRow(anyCollection())).thenReturn(webhookMessageCreateAction)
        Mockito.`when`(webhookMessageCreateAction.addContent(anyString())).thenReturn(webhookMessageCreateAction)
        Mockito.`when`(webhookMessageCreateAction.setEphemeral(anyBoolean())).thenReturn(webhookMessageCreateAction)
        Mockito.`when`(messageChannelUnion.sendMessage(anyString())).thenReturn(messageCreateAction)
        Mockito.`when`(messageCreateAction.addContent(anyString())).thenReturn(messageCreateAction)
        Mockito.`when`(guild.jda).thenReturn(jda)
        Mockito.`when`(guild.idLong).thenReturn(1L)
        Mockito.`when`(guild.owner).thenReturn(member)
        Mockito.`when`(requestingUserDto.guildId).thenReturn(1L)
        //refers to toby-bot usually
        Mockito.`when`(guild.selfMember).thenReturn(botMember)
        Mockito.`when`(member.nickname).thenReturn("Member Nickname")
        Mockito.`when`(member.effectiveName).thenReturn("Effective Name")
        Mockito.`when`(member.guild).thenReturn(guild)
        Mockito.`when`(targetMember.nickname).thenReturn("Target Nickname")
        Mockito.`when`(targetMember.effectiveName).thenReturn("Target Effective Name")
        Mockito.`when`(targetMember.guild).thenReturn(guild)
        Mockito.`when`(botMember.nickname).thenReturn("Bot Nickname")
        Mockito.`when`(botMember.effectiveName).thenReturn("Bot Effective Name")
        Mockito.`when`(botMember.guild).thenReturn(guild)
        Mockito.`when`(requestingUserDto.superUser).thenReturn(true)
        Mockito.`when`(requestingUserDto.memePermission).thenReturn(true)
        Mockito.`when`(requestingUserDto.musicPermission).thenReturn(true)
        Mockito.`when`(requestingUserDto.digPermission).thenReturn(true)
        Mockito.`when`(requestingUserDto.discordId).thenReturn(1L)
        Mockito.`when`(requestingUserDto.guildId).thenReturn(1L)
        Mockito.`when`(requestingUserDto.socialCredit).thenReturn(0L)
        Mockito.`when`(requestingUserDto.musicDto).thenReturn(null)
    }

    @AfterEach
    fun tearDownCommonMocks() {
        Mockito.reset(event)
        Mockito.reset(user)
        Mockito.reset(requestingUserDto)
        Mockito.reset(guild)
        Mockito.reset(interactionHook)
        Mockito.reset(webhookMessageCreateAction)
        Mockito.reset(jda)
        Mockito.reset(message)
        Mockito.reset(messageCreateAction)
        Mockito.reset(botMember)
        Mockito.reset(member)
        Mockito.reset(targetMember)
        Mockito.reset(replyCallbackAction)
    }

    companion object {
        @Mock
        val event: SlashCommandInteractionEvent = Mockito.mock(
            SlashCommandInteractionEvent::class.java
        )

        @Mock
        val interactionHook: InteractionHook = Mockito.mock(InteractionHook::class.java)

        @Mock
        val message: Message = Mockito.mock(Message::class.java)

        @Mock
        val guild: Guild = Mockito.mock(Guild::class.java)

        @Mock
        val user: User = Mockito.mock(User::class.java)

        @Mock
        val jda: JDA = Mockito.mock(JDA::class.java)

        @Mock
        val member: Member = Mockito.mock(Member::class.java)

        @Mock
        val targetMember: Member = Mockito.mock(Member::class.java)

        @Mock
        val botMember: Member = Mockito.mock(Member::class.java)

        @Mock
        val messageChannelUnion: MessageChannelUnion = Mockito.mock(MessageChannelUnion::class.java)


        @Mock
        val requestingUserDto: UserDto = Mockito.mock(UserDto::class.java)

        @Mock
        val webhookMessageCreateAction: WebhookMessageCreateActionImpl<Message> =
            Mockito.mock(WebhookMessageCreateActionImpl::class.java) as WebhookMessageCreateActionImpl<Message>

        @Mock
        val messageCreateAction: MessageCreateAction = Mockito.mock(MessageCreateAction::class.java)

        @Mock
        val replyCallbackAction: ReplyCallbackAction = Mockito.mock(ReplyCallbackAction::class.java)

        @Mock
        val restAction: RestAction<*>? = Mockito.mock(RestAction::class.java)
    }
}
