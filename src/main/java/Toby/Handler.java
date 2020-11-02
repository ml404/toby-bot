package Toby;

import Toby.Emote.Emotes;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceJoinEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.exceptions.PermissionException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;

import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import static Toby.BotMain.jda;

public class Handler extends ListenerAdapter {


   private AudioPlayerManager playerManager = new DefaultAudioPlayerManager();

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {

        long responseNumber = event.getResponseNumber();//The amount of discord events that JDA has received since the last reconnect.

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


        if (event.isFromType(ChannelType.TEXT))         //If this message was sent to a Guild TextChannel
        {
            //Because we now know that this message was sent in a Guild, we can do guild specific things
            // Note, if you don't check the ChannelType before using these methods, they might return null due
            // the message possibly not being from a Guild!

            Guild guild = event.getGuild();             //The Guild that this message was sent in. (note, in the API, Guilds are Servers)
            TextChannel textChannel = event.getTextChannel(); //The TextChannel that this message was sent to.
            Member member = event.getMember();          //This Member that sent the message. Contains Guild specific information about the User!

            String name;
            if (message.isWebhookMessage()) {
                name = author.getName();                //If this is a Webhook message, then there is no Member associated
            }                                           // with the User, thus we default to the author for name.
            else {
                assert member != null;
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

            if (msg.equals("!roll")) {
                //In this case, we have an example showing how to use the flatMap operator for a RestAction. The operator
                // will provide you with the object that results after you execute your RestAction. As a note, not all RestActions
                // have object returns and will instead have Void returns. You can still use the flatMap operator to run chain another RestAction!

                Random rand = ThreadLocalRandom.current();
                int roll = rand.nextInt(6) + 1; //This results in 1 - 6 (instead of 0 - 5)
                channel.sendMessage("Your roll: " + roll)
                        .flatMap(
                                (v) -> roll <= 3,
                                // Send another message if the roll was bad (less than 3)
                                sentMessage -> channel.sendMessage("...shit be cool\n")
                        )
                        .queue();
            }

            if (msg.startsWith("!kick")) {
                //This is an admin command. That means that it requires specific permissions to use it, in this case
                // it needs Permission.KICK_MEMBERS. We will have a check before we attempt to kick members to see
                // if the logged in account actually has the permission, but considering something could change after our
                // check we should also take into account the possibility that we don't have permission anymore, thus Discord
                // response with a permission failure!
                //We will use the error consumer, the second parameter in queue!

                //We only want to deal with message sent in a Guild.
                if (message.isFromType(ChannelType.TEXT)) {
                    //If no users are provided, we can't kick anyone!
                    if (message.getMentionedUsers().isEmpty()) {
                        channel.sendMessage("You must mention 1 or more Users to shoot").queue();
                    } else {
                        Member selfMember = guild.getSelfMember();  //This is the currently logged in account's Member object.
                        // Very similar to JDA#getSelfUser()!

                        //Now, we the the logged in account doesn't have permission to kick members.. well.. we can't kick!
                        if (!selfMember.hasPermission(Permission.KICK_MEMBERS)) {
                            channel.sendMessage("Sorry! I don't have permission to shoot members in this server!").queue();
                            return; //We jump out of the method instead of using cascading if/else
                        }

                        //Loop over all mentioned users, kicking them one at a time.
                        List<User> mentionedUsers = message.getMentionedUsers();
                        for (User user : mentionedUsers) {
                            Member mentionedMember = guild.getMember(user);  //We get the member object for each mentioned user to kick them!

                            //We need to make sure that we can interact with them. Interacting with a Member means you are higher
                            // in the Role hierarchy than they are. Remember, NO ONE is above the Guild's Owner. (Guild#getOwner())
                            if (!selfMember.canInteract(mentionedMember)) {
                                // use the MessageAction to construct the content in StringBuilder syntax using append calls
                                channel.sendMessage("Cannot kick member: ")
                                        .append(member.getEffectiveName())
                                        .append(", they are higher in the hierarchy than I am!")
                                        .queue();
                                continue;   //Continue to the next mentioned user to be kicked.
                            }

                            //Remember, due to the fact that we're using queue we will never have to deal with RateLimits.
                            // JDA will do it all for you so long as you are using queue!
                            guild.kick(Objects.requireNonNull(member)).queue(
                                    success -> channel.sendMessage("Shot ").append(member.getEffectiveName()).append("!").queue(),
                                    error ->
                                    {
                                        //The failure consumer provides a throwable. In this case we want to check for a PermissionException.
                                        if (error instanceof PermissionException) {
                                            PermissionException pe = (PermissionException) error;
                                            Permission missingPermission = pe.getPermission();  //If you want to know exactly what permission is missing, this is how.
                                            //Note: some PermissionExceptions have no permission provided, only an error message!

                                            channel.sendMessage("PermissionError shooting [")
                                                    .append(member.getEffectiveName()).append("]: ")
                                                    .append(error.getMessage()).queue();
                                        } else {
                                            channel.sendMessage("Unknown error while shooting [")
                                                    .append(member.getEffectiveName())
                                                    .append("]: <").append(error.getClass().getSimpleName()).append(">: ")
                                                    .append(error.getMessage()).queue();
                                        }
                                    });
                        }
                    }
                } else {
                    channel.sendMessage("This is a Server-Only command!").queue();
                }
            }
        }
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

