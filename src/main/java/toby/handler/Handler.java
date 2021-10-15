package toby.handler;

import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.ReadyEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceJoinEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceLeaveEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceMoveEvent;
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
import toby.jpa.controller.ConsumeWebService;
import toby.jpa.dto.ConfigDto;
import toby.jpa.dto.MusicDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IBrotherService;
import toby.jpa.service.IConfigService;
import toby.jpa.service.IMusicFileService;
import toby.jpa.service.IUserService;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;
import toby.managers.CommandManager;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Configurable
public class Handler extends ListenerAdapter {

    private IConfigService configService;
    private IBrotherService brotherService;
    private IUserService userService;
    private IMusicFileService musicFileService;
    private static final Logger LOGGER = LoggerFactory.getLogger(Handler.class);
    private final CommandManager manager;

    @Autowired
    public Handler(IConfigService configService, IBrotherService brotherService, IUserService userService, IMusicFileService musicFileService, EventWaiter waiter) {

        manager = new CommandManager(configService, brotherService, userService, musicFileService, waiter);
        this.configService = configService;
        this.brotherService = brotherService;
        this.userService = userService;
        this.musicFileService = musicFileService;
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

    //Auto joining voice channel when it becomes occupied and an audio connection doesn't already exist on the server, then play the associated user's intro song
    @Override
    public void onGuildVoiceJoin(GuildVoiceJoinEvent event) {
        Guild guild = event.getGuild();
        AudioManager audioManager = guild.getAudioManager();
        String volumePropertyName = ConfigDto.Configurations.VOLUME.getConfigValue();
        ConfigDto databaseVolumeConfig = configService.getConfigByName(volumePropertyName, event.getGuild().getId());
        int defaultVolume = databaseVolumeConfig != null ? Integer.parseInt(databaseVolumeConfig.getValue()) : 100;
        List<Member> nonBotConnectedMembers = event.getChannelJoined().getMembers().stream().filter(member -> !member.getUser().isBot()).collect(Collectors.toList());
        AudioPlayer audioPlayer = PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer();
        if (!nonBotConnectedMembers.isEmpty() && !audioManager.isConnected()) {
            audioPlayer.setVolume(defaultVolume);
            audioManager.openAudioConnection(event.getChannelJoined());
        }
        Member member = event.getMember();
        long discordId = member.getUser().getIdLong();
        long guildId = member.getGuild().getIdLong();

        if (Objects.equals(audioManager.getConnectedChannel(), event.getChannelJoined())) {
            playUserIntro(guild, discordId, guildId);
        }
    }

    private void playUserIntro(Guild guild, long discordId, long guildId) {
        UserDto dbUser = userService.getUserById(discordId, guildId);
        MusicDto musicDto = dbUser.getMusicDto();
        if (musicDto != null && musicDto.getFileName() != null) {
            PlayerManager.getInstance().loadAndPlay(guild.getSystemChannel(),
                    String.format(ConsumeWebService.getWebUrl() + "/music?id=%s", musicDto.getId()),
                    0);
        } else if (musicDto != null) {
            PlayerManager.getInstance().loadAndPlay(guild.getSystemChannel(), Arrays.toString(dbUser.getMusicDto().getMusicBlob()),
                    0);
        }
    }


    @Override
    public void onGuildVoiceMove(GuildVoiceMoveEvent event) {
        Guild guild = event.getGuild();
        AudioManager audioManager = guild.getAudioManager();
        String volumePropertyName = ConfigDto.Configurations.VOLUME.getConfigValue();
        ConfigDto databaseConfig = configService.getConfigByName(volumePropertyName, event.getGuild().getId());
        int defaultVolume = databaseConfig != null ? Integer.parseInt(databaseConfig.getValue()) : 100;
        List<Member> nonBotConnectedMembersInOldChannel = event.getChannelLeft().getMembers().stream().filter(member -> !member.getUser().isBot()).collect(Collectors.toList());
        if (Objects.equals(audioManager.getConnectedChannel(), event.getChannelLeft()) && nonBotConnectedMembersInOldChannel.isEmpty()) {
            closeAudioPlayer(guild, audioManager, defaultVolume);
        }
        List<Member> nonBotConnectedMembers = event.getChannelJoined().getMembers().stream().filter(member -> !member.getUser().isBot()).collect(Collectors.toList());
        if (!nonBotConnectedMembers.isEmpty() && !audioManager.isConnected()) {
            PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer().setVolume(defaultVolume);
            audioManager.openAudioConnection(event.getChannelJoined());
        }
    }

    //Auto leaving voice channel when it becomes empty
    @Override
    public void onGuildVoiceLeave(GuildVoiceLeaveEvent event) {
        Guild guild = event.getGuild();
        AudioManager audioManager = guild.getAudioManager();
        String volumePropertyName = ConfigDto.Configurations.VOLUME.getConfigValue();
        ConfigDto databaseConfig = configService.getConfigByName(volumePropertyName, event.getGuild().getId());
        int defaultVolume = databaseConfig != null ? Integer.parseInt(databaseConfig.getValue()) : 100;
        List<Member> nonBotConnectedMembers = event.getChannelLeft().getMembers().stream().filter(member -> !member.getUser().isBot()).collect(Collectors.toList());
        if (Objects.equals(audioManager.getConnectedChannel(), event.getChannelLeft()) && nonBotConnectedMembers.isEmpty()) {
            closeAudioPlayer(guild, audioManager, defaultVolume);
        }
    }

    private void closeAudioPlayer(Guild guild, AudioManager audioManager, int defaultVolume) {
        GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(guild);
        musicManager.getScheduler().setLooping(false);
        musicManager.getScheduler().getQueue().clear();
        musicManager.getAudioPlayer().stopTrack();
        musicManager.getAudioPlayer().setVolume(defaultVolume);
        audioManager.closeAudioConnection();
    }
}

