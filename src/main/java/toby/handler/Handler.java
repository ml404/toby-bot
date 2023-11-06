package toby.handler;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.guild.GuildAvailableEvent;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.stereotype.Service;
import toby.BotMain;
import toby.emote.Emotes;
import toby.helpers.HttpHelper;
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

import static toby.command.commands.fetch.DnDCommand.doLookUpAndReply;
import static toby.helpers.MusicPlayerHelper.playUserIntroWithChannel;
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
    public Handler(IConfigService configService, IBrotherService brotherService, IUserService userService, IMusicFileService musicFileService, IExcuseService excuseService) {
        manager = new CommandManager(configService, brotherService, userService, musicFileService, excuseService);
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

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        User user = event.getUser();
        if (user.isBot()) {
            return;
        }
        manager.handle(event);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        User user = event.getUser();
        if (user.isBot()) {
            return;
        }
        manager.handle(event);
    }

    @Override
    public void onGuildReady(@NotNull GuildReadyEvent event) {
        event.getGuild().updateCommands().addCommands(manager.getAllSlashCommands()).queue();
    }

    @Override
    public void onGuildJoin(@NotNull GuildJoinEvent event) {
        event.getGuild().updateCommands().addCommands(manager.getAllSlashCommands()).queue();
    }

    @Override
    public void onGuildAvailable(@NotNull GuildAvailableEvent event) {
        event.getGuild().updateCommands().addCommands(manager.getAllSlashCommands()).queue();
    }

    private void messageContainsRespond(Message message, MessageChannel channel, String name, Emoji tobyEmote, Emoji jessEmote) {
        String messageStringLowercase = message.getContentRaw().toLowerCase();
        if (messageStringLowercase.contains("toby") || messageStringLowercase.contains("tobs")) {
            message.addReaction(tobyEmote).queue();
            channel.sendMessageFormat("%s... that's not my name %s", name, tobyEmote).queue();
        }

        if (messageStringLowercase.trim().contains("sigh")) {
            channel.sendMessageFormat("Hey %s, what's up champ?", name).queue();
            channel.sendMessageFormat("%s", jessEmote).queue();
        }

        if (messageStringLowercase.contains("yeah")) {
            channel.sendMessage("YEAH????").queue();
        }

        if (message.getMentions().isMentioned(BotMain.getJda().getSelfUser())) {
            channel.sendMessage("Don't talk to me").queue();
        }

        if (messageStringLowercase.contains("covid") || messageStringLowercase.contains("corona")) {
            channel.sendMessage("""
                    It is the 2nd millennium, for more than two years humanity has sat immobile on it's fat arse whilst COVID roams the planet. They are the masters of Netflix by the will of the settee, and masters of ordering Chinese takeaway through the might of their wallets. They are fattening imbeciles imbued with energy from last nights curry. They are the isolated ones for whom more than a million people wear masks every day.

                    Yet, even in their quarantined state, they continue to make everyone's lives miserable. Arsehole scalpers plunder the Internet for the latest ps5 stock, hoarding until they can raise the prices further still. Greatest among these cretins are the anti-vaxxers, complete morons, idiots with IQs less than a goat. Their fools in arms are endless: flat earthers and countless moon landing deniers, the stubborn Facebook politicians and the Karen's of every shopping centre to name only a few. And with all this nonsense they won't shut up about 5G, Immigrants, muh freedoms... and far, far worse.

                    To be a man (or woman) in such times is to be one amongst almost 7 billion. It is to live in the stupidest and most irritating regime imaginable. These are the memes of these times. Forget the power of technology and science, for so much will be denied, never to be acknowledged. Forget the promise of immunity and vaccinations, for in our grim dark daily lives, there is only COVID. There is no freedom on our streets, only an eternity of mask mandates and sanitizer, and the coughing of the sick.""").queue();
        }
    }


    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        if (event.getChannelJoined() != null) {
            onGuildVoiceJoin(event);
        }
        if (event.getChannelLeft() != null) {
            onGuildVoiceLeave(event);
        }
        if (event.getChannelJoined() == null && event.getChannelLeft() == null) {
            onGuildVoiceMove(event);
        }
    }

    //Auto joining voice channel when it becomes occupied and an audio connection doesn't already exist on the server, then play the associated user's intro song

    public void onGuildVoiceJoin(GuildVoiceUpdateEvent event) {
        Guild guild = event.getGuild();
        AudioManager audioManager = guild.getAudioManager();
        String volumePropertyName = ConfigDto.Configurations.VOLUME.getConfigValue();
        ConfigDto databaseVolumeConfig = configService.getConfigByName(volumePropertyName, event.getGuild().getId());
        ConfigDto deleteDelayConfig = configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.getConfigValue(), event.getGuild().getId());

        int defaultVolume = databaseVolumeConfig != null ? Integer.parseInt(databaseVolumeConfig.getValue()) : 100;
        List<Member> nonBotConnectedMembers = event.getChannelJoined().getMembers().stream().filter(member -> !member.getUser().isBot()).toList();
        AudioPlayer audioPlayer = PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer();
        if (!nonBotConnectedMembers.isEmpty() && !audioManager.isConnected()) {
            audioPlayer.setVolume(defaultVolume);
            audioManager.openAudioConnection(event.getChannelJoined());
        }
        Member member = event.getMember();
        long discordId = member.getUser().getIdLong();
        long guildId = member.getGuild().getIdLong();
        UserDto requestingUserDto = calculateUserDto(guildId, discordId, Objects.requireNonNull(event.getMember()).isOwner(), userService, defaultVolume);

        if (Objects.equals(audioManager.getConnectedChannel(), event.getChannelJoined())) {
            playUserIntroWithChannel(requestingUserDto, guild, guild.getDefaultChannel().asTextChannel(), Integer.parseInt(deleteDelayConfig.getValue()), 0L);
        }
    }

    public void onGuildVoiceMove(GuildVoiceUpdateEvent event) {
        Guild guild = event.getGuild();
        AudioManager audioManager = guild.getAudioManager();
        String volumePropertyName = ConfigDto.Configurations.VOLUME.getConfigValue();
        ConfigDto databaseConfig = configService.getConfigByName(volumePropertyName, event.getGuild().getId());
        int defaultVolume = databaseConfig != null ? Integer.parseInt(databaseConfig.getValue()) : 100;
        List<Member> nonBotConnectedMembersInOldChannel = event.getChannelLeft().getMembers().stream().filter(member -> !member.getUser().isBot()).collect(Collectors.toList());
        closeAudioManagerIfChannelEmpty(guild, audioManager, defaultVolume, nonBotConnectedMembersInOldChannel, event.getChannelLeft());
        List<Member> nonBotConnectedMembers = event.getChannelJoined().getMembers().stream().filter(member -> !member.getUser().isBot()).toList();
        if (!nonBotConnectedMembers.isEmpty() && !audioManager.isConnected()) {
            PlayerManager.getInstance().getMusicManager(guild).getAudioPlayer().setVolume(defaultVolume);
            audioManager.openAudioConnection(event.getChannelJoined());
        }
        deleteTemporaryChannelIfEmpty(nonBotConnectedMembers.isEmpty(), event.getChannelLeft());
    }


    //Auto leaving voice channel when it becomes empty
    public void onGuildVoiceLeave(GuildVoiceUpdateEvent event) {
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

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        event.deferReply().queue();
        if (event.getComponentId().equals("DnDspells")) {
            sendDndApiRequest(event, "spells");
        }
        if (event.getComponentId().equals("DnDconditions")) {
            sendDndApiRequest(event, "conditions");
        }
    }

    private void sendDndApiRequest(StringSelectInteractionEvent event, String spells) {
        String selectedValue = event.getValues().get(0); // Get the selected option
        ConfigDto deleteDelayConfig = configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.getConfigValue(), event.getGuild().getId());
        event.getMessage().delete().queue();
        doLookUpAndReply(event.getHook(), spells, selectedValue, new HttpHelper(), Integer.valueOf(deleteDelayConfig.getValue()));
    }

}