package toby;

import me.duncte123.botcommons.messaging.EmbedUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
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

        JDABuilder builder = JDABuilder.createDefault(BotConfig.get("token"),
                GatewayIntent.GUILD_MEMBERS,
                GatewayIntent.GUILD_MESSAGES
                ).disableCache(CacheFlag.CLIENT_STATUS,
                CacheFlag.ACTIVITY
                ).enableCache(CacheFlag.VOICE_STATE, CacheFlag.EMOTE);
        builder.addEventListeners(new Handler());
        jda = builder.build();
    }

    //    private boolean shuttingDown = false;


    public static void main(String[] args) throws LoginException {
        new BotMain();
    }
}

