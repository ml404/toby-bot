package toby;

import me.duncte123.botcommons.messaging.EmbedUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import toby.handler.Handler;

import javax.security.auth.login.LoginException;

public class BotMain {
    public static JDA jda;

    private BotMain() throws LoginException {
        EmbedUtils.setEmbedBuilder(
                () -> new EmbedBuilder()
                        .setColor(0x3883d9)
                        .setFooter("TobyBot")
        );

        JDABuilder builder = JDABuilder.createDefault(BotConfig.get("token"));
        builder.addEventListeners(new Handler());
        jda = builder.build();
    }

    //    private boolean shuttingDown = false;


    public static void main(String[] args) throws LoginException {
        new BotMain();
    }
}

