package bot.configuration

import me.duncte123.botcommons.messaging.EmbedUtils
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.yaml.snakeyaml.error.MissingEnvironmentVariableException
import java.util.*

@Profile("prod")
@Configuration
open class BotConfig {

    @Bean
    open fun jda(): JDA {
        EmbedUtils.setEmbedBuilder {
            EmbedBuilder()
                .setColor(0x3883d9)
                .setFooter("TobyBot")
        }

        val discordToken = System.getenv("TOKEN") ?: throw MissingEnvironmentVariableException("TOKEN environment variable is not set.")

        return JDABuilder.createDefault(
            discordToken,
            GatewayIntent.DIRECT_MESSAGES,
            GatewayIntent.MESSAGE_CONTENT,
            GatewayIntent.GUILD_MESSAGE_TYPING,
            GatewayIntent.GUILD_MEMBERS,
            GatewayIntent.GUILD_WEBHOOKS,
            GatewayIntent.GUILD_PRESENCES,
            GatewayIntent.GUILD_MESSAGES,
            GatewayIntent.GUILD_MESSAGE_REACTIONS,
            GatewayIntent.GUILD_VOICE_STATES,
            GatewayIntent.GUILD_EMOJIS_AND_STICKERS
        )
            .setMemberCachePolicy(MemberCachePolicy.ALL)
            .disableCache(
                EnumSet.of(
                    CacheFlag.CLIENT_STATUS,
                    CacheFlag.ACTIVITY,
                    CacheFlag.SCHEDULED_EVENTS
                )
            )
            .enableCache(CacheFlag.VOICE_STATE, CacheFlag.EMOJI)
            .build()
    }
}