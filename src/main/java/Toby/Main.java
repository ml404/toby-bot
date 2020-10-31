package Toby;

import Toby.Emote.Emotes;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Emote;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceJoinEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import javax.security.auth.login.LoginException;

public class Main extends ListenerAdapter {

    private boolean shuttingDown = false;
    private static JDA jda;


    public static void main(String[] args) throws LoginException {
        String token = "NTUzNjU4MDM5MjY2NDQzMjY0.D2RTSA._rvjb2-d1hxBXF55jH4is4_VHGQ";
        JDABuilder builder = JDABuilder.createDefault(token);
        builder.addEventListeners(new Main());
        jda = builder.build();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }
        Emote tobyEmote = event.getGuild().getJDA().getEmoteById(Emotes.TOBY);
        Emote jessEmote = event.getGuild().getJDA().getEmoteById(Emotes.JESS);

        if (event.getMessage().getContentRaw().toLowerCase().contains("toby")) {
            event.getMessage().addReaction(tobyEmote).queue();
            event.getChannel().sendMessage(String.format("%s... that's not my name %s", event.getAuthor(), tobyEmote)).queue();
        }

        if (event.getMessage().getContentRaw().toLowerCase().trim().contains("sigh")) {
            event.getChannel().sendMessage(String.format("Hey %s, what's up champ?", event.getAuthor())).queue();
            event.getChannel().sendMessage(String.format("%s",  jessEmote)).queue();
        }

        if (event.getMessage().getContentRaw().toLowerCase().contains("yeah")) {
            event.getChannel().sendMessage("YEAH????").queue();
        }

    }

    @Override
    public void onGuildVoiceJoin(GuildVoiceJoinEvent event) {
        event.getChannelJoined();
    }

    public void shutdown() {
        if (shuttingDown)
            return;
        shuttingDown = true;
        if (jda.getStatus() != JDA.Status.SHUTTING_DOWN) {
            jda.getGuilds().stream().forEach(g ->
            {
                jda.shutdown();
            });

            System.exit(0);
        }
    }
}

