package toby.handler;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.ReadyEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceLeaveEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.stereotype.Service;
import toby.BotMain;
import toby.emote.Emotes;
import toby.jpa.service.IBrotherService;
import toby.jpa.service.IConfigService;
import toby.jpa.service.IUserService;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;
import toby.managers.CommandManager;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Configurable
public class Handler extends ListenerAdapter {

    private final AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
    private IConfigService configService;
    private IBrotherService brotherService;
    private IUserService userService;
    private static final Logger LOGGER = LoggerFactory.getLogger(Handler.class);
    private final CommandManager manager;

    @Autowired
    public Handler(IConfigService configService, IBrotherService brotherService, IUserService userService) {

        manager = new CommandManager(configService, brotherService, userService);
        this.configService = configService;
        this.brotherService = brotherService;
        this.userService = userService;
    }


    @Override
    public void onReady(@Nonnull ReadyEvent event) {
        LOGGER.info("{} is ready", event.getJDA().getSelfUser().getAsTag());
    }

    @Override
    public void onGuildMessageReceived(@Nonnull GuildMessageReceivedEvent event) {
        User user = event.getAuthor();

        if (user.isBot() || event.isWebhookMessage()) {
            return;
        }

        String prefix = configService.getConfigByName("PREFIX", event.getGuild().getId()).getValue();
        String raw = event.getMessage().getContentRaw();

        if (raw.startsWith(prefix)) {
            manager.handle(event);
        }

        //Event specific information
        User author = event.getAuthor();                //The user that sent the message
        Message message = event.getMessage();           //The message that was received.
        MessageChannel channel = event.getChannel();    //This is the MessageChannel that the message was sent to.
        //  This could be a TextChannel, PrivateChannel, or Group!

        String msg = message.getContentDisplay();              //This returns a human readable version of the Message. Similar to
        // what you would see in the client.

        boolean bot = author.isBot();                    //This boolean is useful to determine if the User that
        // sent the Message is a BOT or not!


        if (bot) {
            return;
        }
        Guild guild = event.getGuild();             //The Guild that this message was sent in. (note, in the API, Guilds are Servers)
        TextChannel textChannel = event.getChannel(); //The TextChannel that this message was sent to.
        Member member = event.getMember();          //This Member that sent the message. Contains Guild specific information about the User!

        String name;
        if (message.isWebhookMessage()) {
            name = author.getName();                //If this is a Webhook message, then there is no Member associated
        }                                           // with the User, thus we default to the author for name.
        else {
            name = member.getEffectiveName();       //This will either use the Member's nickname if they have one,
        }                                           // otherwise it will default to their username. (User#getName())

        JDA jda = guild.getJDA();
        Emote tobyEmote = jda.getEmoteById(Emotes.TOBY);
        Emote jessEmote = jda.getEmoteById(Emotes.JESS);

        if (message.getContentRaw().toLowerCase().contains("toby") || message.getContentRaw().toLowerCase().contains("tobs")) {
            message.addReaction(tobyEmote).queue();
            channel.sendMessage(String.format("%s... that's not my name %s", name, tobyEmote)).queue();
        }

        if (message.getContentRaw().toLowerCase().trim().contains("sigh")) {
            channel.sendMessage(String.format("Hey %s, what's up champ?", name)).queue();
            channel.sendMessage(String.format("%s", jessEmote)).queue();
        }

        if (message.getContentRaw().toLowerCase().contains("yeah")) {
            channel.sendMessage("YEAH????").queue();
        }

        if (message.isMentioned(BotMain.getJda().getSelfUser())) {
            channel.sendMessage("Don't talk to me").queue();
        }

    }


    @Override
    public void onMessageReceived(MessageReceivedEvent event) {

        long responseNumber = event.getResponseNumber();//The amount of discord events that JDA has received since the last reconnect.


    }

    //    Need this for auto leaving voice channel when it becomes empty
    @Override
    public void onGuildVoiceLeave(GuildVoiceLeaveEvent event) {
        Guild guild = event.getGuild();
        AudioManager audioManager = guild.getAudioManager();
        List<Member> nonBotConnectedMembers = event.getChannelLeft().getMembers().stream().filter(member -> !member.getUser().isBot()).collect(Collectors.toList());
        if (Objects.equals(audioManager.getConnectedChannel(), event.getChannelLeft()) && nonBotConnectedMembers.isEmpty()) {
            GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(guild);
            musicManager.getScheduler().setLooping(false);
            musicManager.getScheduler().getQueue().clear();
            musicManager.getAudioPlayer().stopTrack();
            musicManager.getAudioPlayer().setVolume(100);
            audioManager.closeAudioConnection();
        }
    }
}

