package toby.handler;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.ReadyEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceJoinEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import toby.BotConfig;
import toby.CommandManager;
import toby.emote.Emotes;

import javax.annotation.Nonnull;

import static toby.BotMain.jda;

public class Handler extends ListenerAdapter {


    private AudioPlayerManager playerManager = new DefaultAudioPlayerManager();

    private static final Logger LOGGER = LoggerFactory.getLogger(Handler.class);
    private final CommandManager manager = new CommandManager();

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

        String prefix = BotConfig.get("prefix");
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

            Emote tobyEmote = guild.getJDA().getEmoteById(Emotes.TOBY);
            Emote jessEmote = guild.getJDA().getEmoteById(Emotes.JESS);

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

            if (message.isMentioned(jda.getSelfUser())) {
                channel.sendMessage("Don't talk to me").queue();
            }

        }


    @Override
    public void onMessageReceived(MessageReceivedEvent event) {

        long responseNumber = event.getResponseNumber();//The amount of discord events that JDA has received since the last reconnect.


    }

    @Override
    public void onGuildVoiceJoin(GuildVoiceJoinEvent event) {

        AudioSourceManagers.registerRemoteSources(playerManager);
        AudioPlayer player = playerManager.createPlayer();

        // Creates a variable equal to the channel that the user is in.
        VoiceChannel connectedChannel = event.getMember().getVoiceState().getChannel();
        AudioManager audioManager = event.getGuild().getAudioManager();
        audioManager.openAudioConnection(connectedChannel);

        event.getGuild().getAudioManager().closeAudioConnection();

    }
}

