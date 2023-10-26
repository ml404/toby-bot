package toby.command.commands;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.internal.requests.restaction.WebhookMessageCreateActionImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import toby.jpa.dto.UserDto;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public interface CommandTest {

    @Mock
    SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);

    @Mock
    InteractionHook interactionHook = mock(InteractionHook.class);

    @Mock
    Message message = mock(Message.class);

    @Mock
    Guild guild = mock(Guild.class);

    @Mock
    User user = mock(User.class);

    @Mock
    JDA jda = mock(JDA.class);

    @Mock
    Member member = mock(Member.class);
    @Mock
    Member targetMember = mock(Member.class);
    @Mock
    Member botMember = mock(Member.class);
    @Mock
    MessageChannelUnion messageChannelUnion = mock(MessageChannelUnion.class);


    @Mock UserDto requestingUserDto = mock(UserDto.class);

    @Mock
    WebhookMessageCreateAction<Message> webhookMessageCreateAction = mock(WebhookMessageCreateActionImpl.class);

    @Mock
    MessageCreateAction messageCreateAction = mock(MessageCreateAction.class);

    @Mock ReplyCallbackAction replyCallbackAction = mock(ReplyCallbackAction.class);
    @Mock
    RestAction restAction = mock(RestAction.class);


    @BeforeEach
    default void setUpCommonMocks() {
        when(event.getHook()).thenReturn(interactionHook);
        when(event.deferReply()).thenReturn(replyCallbackAction);
        when(event.getGuild()).thenReturn(guild);
        when(event.getUser()).thenReturn(user);
        when(event.reply(anyString())).thenReturn(replyCallbackAction);
        when(event.replyFormat(anyString(), any())).thenReturn(replyCallbackAction);
        //refers to the user making the call
        when(event.getMember()).thenReturn(member);
        when(event.getChannel()).thenReturn(messageChannelUnion);
        when(user.getEffectiveName()).thenReturn("UserName");
        when(user.getName()).thenReturn("UserName");
        when(interactionHook.deleteOriginal()).thenReturn(restAction);
        when(interactionHook.sendMessage(anyString())).thenReturn(webhookMessageCreateAction);
        when(interactionHook.sendMessageFormat(anyString(), any(Object[].class))).thenReturn(webhookMessageCreateAction);
        when(interactionHook.retrieveOriginal()).thenReturn(restAction);
        when(webhookMessageCreateAction.addActionRow(anyCollection())).thenReturn(webhookMessageCreateAction);
        when(webhookMessageCreateAction.addContent(anyString())).thenReturn(webhookMessageCreateAction);
        when(webhookMessageCreateAction.setEphemeral(anyBoolean())).thenReturn(webhookMessageCreateAction);
        when(messageChannelUnion.sendMessage(anyString())).thenReturn(messageCreateAction);
        when(messageCreateAction.addContent(anyString())).thenReturn(messageCreateAction);
        when(guild.getJDA()).thenReturn(jda);
        when(guild.getIdLong()).thenReturn(1L);
        when(guild.getOwner()).thenReturn(member);
        when(guild.getId()).thenReturn("1");
        //refers to toby-bot usually
        when(guild.getSelfMember()).thenReturn(botMember);
        when(member.getNickname()).thenReturn("Member Nickname");
        when(member.getEffectiveName()).thenReturn("Effective Name");
        when(member.getGuild()).thenReturn(guild);
        when(targetMember.getNickname()).thenReturn("Target Nickname");
        when(targetMember.getEffectiveName()).thenReturn("Target Effective Name");
        when(targetMember.getGuild()).thenReturn(guild);
        when(botMember.getNickname()).thenReturn("Bot Nickname");
        when(botMember.getEffectiveName()).thenReturn("Bot Effective Name");
        when(botMember.getGuild()).thenReturn(guild);
        when(requestingUserDto.isSuperUser()).thenReturn(true);
        when(requestingUserDto.hasMemePermission()).thenReturn(true);
        when(requestingUserDto.hasMusicPermission()).thenReturn(true);
        when(requestingUserDto.hasDigPermission()).thenReturn(true);
        when(requestingUserDto.getDiscordId()).thenReturn(1L);
        when(requestingUserDto.getGuildId()).thenReturn(1L);
        when(requestingUserDto.getSocialCredit()).thenReturn(0L);
        when(requestingUserDto.getMusicDto()).thenReturn(null);


    }

    @AfterEach
    default void tearDownCommonMocks(){
        reset(event);
        reset(user);
        reset(requestingUserDto);
        reset(guild);
        reset(interactionHook);
        reset(webhookMessageCreateAction);
        reset(jda);
        reset(message);
        reset(messageCreateAction);
        reset(botMember);
        reset(member);
        reset(targetMember);
        reset(replyCallbackAction);
    }

}
