package toby;

import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import me.duncte123.botcommons.messaging.EmbedUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import toby.handler.Handler;

import javax.security.auth.login.LoginException;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.EnumSet;

import static toby.DatabaseHelper.getConnection;

public class BotMain {
    public static JDA jda;
    public static Connection connection;

    private BotMain() throws LoginException, SQLException {
        EmbedUtils.setEmbedBuilder(
                () -> new EmbedBuilder()
                        .setColor(0x3883d9)
                        .setFooter("TobyBot")
        );

        EventWaiter waiter = new EventWaiter();
        try {
            connection = getConnection();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        assert connection != null;
        String token = DatabaseHelper.getConfigValue("TOKEN");

        JDABuilder builder = JDABuilder.createDefault(token,
                GatewayIntent.GUILD_MEMBERS,
                GatewayIntent.GUILD_MESSAGES,
                GatewayIntent.GUILD_MESSAGE_REACTIONS,
                GatewayIntent.GUILD_VOICE_STATES,
                GatewayIntent.GUILD_EMOJIS
        ).disableCache(EnumSet.of(
                CacheFlag.CLIENT_STATUS,
                CacheFlag.ACTIVITY
        )).enableCache(CacheFlag.VOICE_STATE, CacheFlag.EMOTE);
        builder.addEventListeners(new Handler(waiter), waiter);
        jda = builder.build();
    }

    public static void main(String[] args) throws LoginException, SQLException {
        new BotMain();
    }
}

