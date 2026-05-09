package bot.toby.voice

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class LastConnectedChannelTracker {

    private val channelIdsByGuild = ConcurrentHashMap<Long, Long>()

    fun set(guildId: Long, channelId: Long) {
        channelIdsByGuild[guildId] = channelId
    }

    fun clear(guildId: Long) {
        channelIdsByGuild.remove(guildId)
    }

    fun resolveChannel(guild: Guild): VoiceChannel? {
        val channelId = channelIdsByGuild[guild.idLong] ?: return null
        return guild.getVoiceChannelById(channelId)
    }
}
