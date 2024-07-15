package toby

import me.duncte123.botcommons.messaging.EmbedUtils
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import org.springframework.stereotype.Service
import org.yaml.snakeyaml.error.MissingEnvironmentVariableException
import toby.handler.Handler
import toby.jpa.service.*
import java.util.*

@Service
@Configurable
open class BotMain @Autowired constructor(
    configService: IConfigService,
    brotherService: IBrotherService,
    userService: IUserService,
    musicFileService: IMusicFileService,
    excuseService: IExcuseService
) {
    init {
        EmbedUtils.setEmbedBuilder {
            EmbedBuilder()
                .setColor(0x3883d9)
                .setFooter("TobyBot")
        }

        // Fetch the Discord token from the environment
        val discordToken = System.getenv("TOKEN")
            ?: throw MissingEnvironmentVariableException("TOKEN environment variable is not set.")

        val builder = JDABuilder.createDefault(
            discordToken,
            GatewayIntent.MESSAGE_CONTENT,
            GatewayIntent.GUILD_MESSAGE_TYPING,
            GatewayIntent.GUILD_MEMBERS,
            GatewayIntent.GUILD_WEBHOOKS,
            GatewayIntent.GUILD_PRESENCES,
            GatewayIntent.GUILD_MESSAGES,
            GatewayIntent.GUILD_MESSAGE_REACTIONS,
            GatewayIntent.GUILD_VOICE_STATES,
            GatewayIntent.GUILD_EMOJIS_AND_STICKERS
        ) //TODO: circle back to see if I can do something different here,
            // without this get members only grabs those who are currently connected to a voice channel at time of request
            .setMemberCachePolicy(MemberCachePolicy.ALL)
            .disableCache(
                EnumSet.of(
                    CacheFlag.CLIENT_STATUS,
                    CacheFlag.ACTIVITY
                )
            ).enableCache(CacheFlag.VOICE_STATE, CacheFlag.EMOJI)
        builder.addEventListeners(Handler(configService, brotherService, userService, musicFileService, excuseService))
        jda = builder.build()
    }

    companion object {
        var jda: JDA? = null
    }
}


