package bot.toby.notify

import net.dv8tion.jda.api.JDA

/**
 * Finds the voice channel a PvP notification should land in: the first
 * of [discordIds] (checked in order) currently connected to voice in
 * [guildId], or null when none are.
 *
 * Used as the `originChannelId` hint for web/activity-initiated
 * challenge notifications — when the participants are sitting in a
 * voice channel (the Discord Activity case), the ping belongs in that
 * channel's text chat, not the system channel. Voice-state cache is
 * always warm here: the bot ships the music player, so it runs with
 * voice states cached.
 */
fun JDA.currentVoiceChannelId(guildId: Long, vararg discordIds: Long): Long? {
    val guild = getGuildById(guildId) ?: return null
    for (id in discordIds) {
        guild.getMemberById(id)?.voiceState?.channel?.idLong?.let { return it }
    }
    return null
}
