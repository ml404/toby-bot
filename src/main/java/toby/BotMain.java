package toby;

import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import me.duncte123.botcommons.messaging.EmbedUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import toby.handler.Handler;
import toby.jpa.service.*;

import javax.security.auth.login.LoginException;
import java.util.EnumSet;

@Service
@Configurable
public class BotMain {
    private static JDA jda;

    @Autowired
    public BotMain(IConfigService configService,
                   IBrotherService brotherService,
                   IUserService userService,
                   IMusicFileService musicFileService,
                   IExcuseService excuseService,
                   EventWaiter waiter) throws LoginException {
        EmbedUtils.setEmbedBuilder(
                () -> new EmbedBuilder()
                        .setColor(0x3883d9)
                        .setFooter("TobyBot")
        );

        String token = configService.getConfigByName("TOKEN", "all").getValue();
        JDABuilder builder = JDABuilder.createDefault(token,
                        GatewayIntent.GUILD_MEMBERS,
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.GUILD_MESSAGE_REACTIONS,
                        GatewayIntent.GUILD_VOICE_STATES,
                        GatewayIntent.GUILD_EMOJIS)
                //TODO: circle back to see if I can do something different here,
                // without this get members only grabs those who are currently connected to a voice channel at time of request
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .disableCache(EnumSet.of(
                        CacheFlag.CLIENT_STATUS,
                        CacheFlag.ACTIVITY
                )).enableCache(CacheFlag.VOICE_STATE, CacheFlag.EMOTE);
        builder.addEventListeners(new Handler(configService, brotherService, userService, musicFileService, excuseService, waiter), waiter);
        setJda(builder.build());
    }

    public static JDA getJda() {
        return jda;
    }

    public static void setJda(JDA jda) {
        BotMain.jda = jda;
    }

    @Bean
    public static EventWaiter eventWaiter() {
        return new EventWaiter();
    }

}


