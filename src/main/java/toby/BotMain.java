package toby;

import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import me.duncte123.botcommons.messaging.EmbedUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.stereotype.Service;
import toby.handler.Handler;
import toby.jpa.service.IBrotherService;
import toby.jpa.service.IConfigService;

import javax.security.auth.login.LoginException;
import java.util.EnumSet;

@Service
@Configurable
public class BotMain {
    private static JDA jda;

    @Autowired
    public BotMain(IConfigService configService, IBrotherService brotherService) throws LoginException {
        EmbedUtils.setEmbedBuilder(
                () -> new EmbedBuilder()
                        .setColor(0x3883d9)
                        .setFooter("TobyBot")
        );

        EventWaiter waiter = new EventWaiter();
        String token = configService.getConfigByName("TOKEN").getValue();
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
        builder.addEventListeners(new Handler(configService, brotherService));
        setJda(builder.build());
    }

    public static JDA getJda() {
        return jda;
    }

    public static void setJda(JDA jda) {
        BotMain.jda = jda;
    }
}


