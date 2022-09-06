package toby.handler;

import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.ReadyEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceJoinEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceLeaveEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceMoveEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.stereotype.Service;
import toby.BotMain;
import toby.emote.Emotes;
import toby.jpa.dto.ConfigDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.*;
import toby.lavaplayer.GuildMusicManager;
import toby.lavaplayer.PlayerManager;
import toby.managers.CommandManager;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static toby.helpers.MusicPlayerHelper.playUserIntro;
import static toby.helpers.UserDtoHelper.calculateUserDto;

@Service
@Configurable
public class Handler extends ListenerAdapter {

    private final IConfigService configService;
    private final IBrotherService brotherService;
    private final IUserService userService;
    private final IMusicFileService musicFileService;
    private final IExcuseService excuseService;
    private static final Logger LOGGER = LoggerFactory.getLogger(Handler.class);
    private final CommandManager manager;

    @Autowired
    public Handler(IConfigService configService, IBrotherService brotherService, IUserService userService, IMusicFileService musicFileService, IExcuseService excuseService, EventWaiter waiter) {
        manager = new CommandManager(configService, brotherService, userService, musicFileService, excuseService, waiter);
        this.configService = configService;
        this.brotherService = brotherService;
        this.userService = userService;
        this.musicFileService = musicFileService;
        this.excuseService = excuseService;
    }


    @Override
    public void onReady(@Nonnull ReadyEvent event) {
        LOGGER.info("{} is ready", event.getJDA().getSelfUser().getAsTag());
    }

    @Override
    public void onMessageReceived(@Nonnull MessageReceivedEvent event) {
        User user = event.getAuthor();

        if (user.isBot() || event.isWebhookMessage()) {
            return;
        }

        String prefix = configService.getConfigByName("PREFIX", event.getGuild().getId()).getValue();
        String raw = event.getMessage().getContentRaw();

        if (raw.startsWith(prefix)) {
            event.getGuild().loadMembers();
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
        TextChannel textChannel = event.getChannel().asTextChannel(); //The TextChannel that this message was sent to.
        Member member = event.getMember();          //This Member that sent the message. Contains Guild specific information about the User!

        String name;
        if (message.isWebhookMessage()) {
            name = author.getName();                //If this is a Webhook message, then there is no Member associated
        }                                           // with the User, thus we default to the author for name.
        else {
            name = member.getEffectiveName();       //This will either use the Member's nickname if they have one,
        }                                           // otherwise it will default to their username. (User#getName())

        JDA jda = guild.getJDA();
        Emoji tobyEmote = jda.getEmojiById(Emotes.TOBY);
        Emoji jessEmote = jda.getEmojiById(Emotes.JESS);

        messageContainsRespond(message, channel, name, tobyEmote, jessEmote);

    }

    private void messageContainsRespond(Message message, MessageChannel channel, String name, Emoji tobyEmote, Emoji jessEmote) {
        String messageStringLowercase = message.getContentRaw().toLowerCase();
        if (messageStringLowercase.contains("toby") || messageStringLowercase.contains("tobs")) {
            message.addReaction(tobyEmote).queue();
            channel.sendMessage(String.format("%s... that's not my name %s", name, tobyEmote)).queue();
        }

        if (messageStringLowercase.trim().contains("sigh")) {
            channel.sendMessage(String.format("Hey %s, what's up champ?", name)).queue();
            channel.sendMessage(String.format("%s", jessEmote)).queue();
        }

        if (messageStringLowercase.contains("yeah")) {
            channel.sendMessage("YEAH????").queue();
        }

        if (message.getMentions().isMentioned(BotMain.getJda().getSelfUser())) {
            channel.sendMessage("Don't talk to me").queue();
        }

        if(messageStringLowercase.contains("covid") || messageStringLowercase.contains("corona")){
            channel.sendMessage("It is the 2nd millennium, for more than two years humanity has sat immobile on it's fat arse whilst COVID roams the planet. They are the masters of Netflix by the will of the settee, and masters of ordering Chinese takeaway through the might of their wallets. They are fattening imbeciles imbued with energy from last nights curry. They are the isolated ones for whom more than a million people wear masks every day.\n" +
                    "\n" +
                    "Yet, even in their quarantined state, they continue to make everyone's lives miserable. Arsehole scalpers plunder the Internet for the latest ps5 stock, hoarding until they can raise the prices further still. Greatest amount these cretins are the anti-vaxers, complete morons, idiots with IQs less than a goat. Their fools in arms are endless: flat earthers and countless moon landing deniers, the stubborn Facebook politicians and the Karen's of every shopping centre to name only a few. And with all this nonsense they won't shut up about 5G, Immigrants, muh freedoms... and far, far worse.\n" +
                    "\n" +
                    "To be a man (or woman) in such times is to be one amongst almost 7 billion. It is to live in the stupidest and most irritating regime imaginable. These are the memes of these times. Forget the power of technology and science, for so much will be denied, never to be acknowledged. Forget the promise of immunity and vaccinations, for in our grim dark daily lives, there is only COVID. There is no freedom on our streets, only an eternity of mask mandates and sanitizer, and the coughing of the sick.").queue();
        }
    }

    //Auto joining voice channel when it becomes occupied and an audio connection doesn't already exist on the server, then play the associated user's intro song
    @Override
    public void onGuildVoiceJoin(GuildVoiceJoinEvent event) {
        Guild guild = event.getGuild();
        AudioManager audioManager = guild.getAudioManager();
        String volumePropertyName = ConfigDto.Configurations.VOLUME.getConfigValue();
        ConfigDto databaseVolumeConfig = configService.getConfigByName(volumePropertyName, event.getGuild().getId());
        ConfigDto deleteDelayConfig = configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.getConfigValue(), event.getGuild().getId());

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
        UserDto requestingUserDto = calculateUserDto(guildId, discordId, Objects.requireNonNull(event.getMember()).isOwner(), userService, defaultVolume);

        //TODO guild.getDefaultChannel no longer works if the default channel isn't viewable by guild.getPublicRole() i.e. everyone
        if (Objects.equals(audioManager.getConnectedChannel(), event.getChannelJoined())) {
            playUserIntro(requestingUserDto, guild, guild.getDefaultChannel().asTextChannel(), Integer.parseInt(deleteDelayConfig.getValue()), 0L);
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
        closeAudioManagerIfChannelEmpty(guild, audioManager, defaultVolume, nonBotConnectedMembersInOldChannel, event.getChannelLeft());
        List<Member> nonBotConnectedMembers = event.getChannelJoined().getMembers().stream().filter(member -> !member.getUser().isBot()).collect(Collectors.toList());
        if (!nonBotConnectedMembers.isEmpty() && !audioManager.isConnected()) {
            PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer().setVolume(defaultVolume);
            audioManager.openAudioConnection(event.getChannelJoined());
        }
        deleteTemporaryChannelIfEmpty(nonBotConnectedMembers.isEmpty(), event.getChannelLeft());
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
        closeAudioManagerIfChannelEmpty(guild, audioManager, defaultVolume, nonBotConnectedMembers, event.getChannelLeft());
        deleteTemporaryChannelIfEmpty(nonBotConnectedMembers.isEmpty(), event.getChannelLeft());
    }

    private void closeAudioPlayer(Guild guild, AudioManager audioManager, int defaultVolume) {
        GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(guild);
        musicManager.getScheduler().setLooping(false);
        musicManager.getScheduler().getQueue().clear();
        musicManager.getAudioPlayer().stopTrack();
        musicManager.getAudioPlayer().setVolume(defaultVolume);
        audioManager.closeAudioConnection();
    }

    private void closeAudioManagerIfChannelEmpty(Guild guild, AudioManager audioManager, int defaultVolume, List<Member> nonBotConnectedMembers, AudioChannel channelLeft) {
        if (Objects.equals(audioManager.getConnectedChannel(), channelLeft) && nonBotConnectedMembers.isEmpty()) {
            closeAudioPlayer(guild, audioManager, defaultVolume);
        }
    }

    private void deleteTemporaryChannelIfEmpty(boolean nonBotConnectedMembersEmpty, AudioChannel channelLeft) {
        //The autogenerated channels from the team command are "Team #", so this will delete them once they become empty
        if (channelLeft.getName().matches("(?i)team\\s[0-9]+") && nonBotConnectedMembersEmpty) {
            channelLeft.delete().queue();
        }
    }
}

