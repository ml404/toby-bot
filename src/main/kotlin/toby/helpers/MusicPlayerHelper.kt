package toby.helpers

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.Channel
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import net.dv8tion.jda.api.interactions.components.buttons.Button
import org.springframework.web.ErrorResponseException
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.command.commands.music.IMusicCommand.Companion.sendDeniedStoppableMessage
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.lavaplayer.GuildMusicManager
import toby.lavaplayer.PlayerManager
import java.net.URI
import java.util.concurrent.TimeUnit

object MusicPlayerHelper {
    private const val webUrl = "https://gibe-toby-bot.herokuapp.com/"

    const val SECOND_MULTIPLIER: Int = 1000
    private val guildLastNowPlayingMessage: MutableMap<Long, MutableMap<Channel?, Message?>> = HashMap()

    @JvmStatic
    fun playUserIntro(dbUser: UserDto, guild: Guild?, deleteDelay: Int, startPosition: Long?, volume: Int) {
        playUserIntro(dbUser, guild, null, deleteDelay, startPosition, volume)
    }

    @JvmStatic
    fun playUserIntro(
        dbUser: UserDto,
        guild: Guild?,
        event: SlashCommandInteractionEvent?,
        deleteDelay: Int,
        startPosition: Long?,
        volume: Int
    ) {
        val musicDto = dbUser.musicDto
        val instance = PlayerManager.instance
        val currentVolume = PlayerManager.instance.getMusicManager(guild!!).audioPlayer.volume
        if (musicDto?.fileName != null) {
            val introVolume = musicDto.introVolume
            instance.setPreviousVolume(currentVolume)
            instance.loadAndPlay(
                guild,
                event,
                "$webUrl/music?id=${musicDto.id}",
                true,
                0,
                startPosition!!,
                introVolume
            )
        } else if (musicDto != null) {
            val introVolume = musicDto.introVolume
            instance.setPreviousVolume(currentVolume)
            instance.loadAndPlay(
                guild,
                event,
                dbUser.musicDto?.musicBlob.contentToString(),
                true,
                deleteDelay,
                startPosition!!,
                introVolume
            )
        }
    }

    @JvmStatic
    fun nowPlaying(event: IReplyCallback, playerManager: PlayerManager, deleteDelay: Int?) {
        val musicManager = playerManager.getMusicManager(event.guild!!)
        val audioPlayer = musicManager.audioPlayer
        val track = audioPlayer.playingTrack
        val hook = event.hook
        if (checkForPlayingTrack(track, hook, deleteDelay)) return
        checkTrackAndSendMessage(track, hook, audioPlayer.volume)
    }

    private fun checkTrackAndSendMessage(track: AudioTrack, hook: InteractionHook, volume: Int) {
        val nowPlaying = getNowPlayingString(track, volume)
        val pausePlay = Button.primary("pause/play", "⏯")
        val stop = Button.primary("stop", "⏹")
        val interaction = hook.interaction
        val guildId = interaction.guild!!.idLong
        val channel = interaction.channel

        // Get the previous "Now Playing" messages if they exist
        val channelMessageMap: Map<Channel?, Message?>? = guildLastNowPlayingMessage[guildId]
        try {
            if (channelMessageMap == null || channelMessageMap[channel] == null) {
                sendNewNowPlayingMessage(hook, channel, nowPlaying, pausePlay, stop, guildId)
            } else {
                // Update the existing "Now Playing" messages
                val updatedNowPlaying = getNowPlayingString(track, volume)
                for (message in channelMessageMap.values) {
                    try {
                        message!!.editMessage(updatedNowPlaying).setActionRow(pausePlay, stop).queue()
                    } catch (e: ErrorResponseException) {
                        // Log exception or handle accordingly
                    }
                }
                hook.deleteOriginal().queue()
            }
        } catch (e: IllegalArgumentException) {
            // Send a new "Now Playing" message and store it
            sendNewNowPlayingMessage(hook, channel, nowPlaying, pausePlay, stop, guildId)
        } catch (e: ErrorResponseException) {
            sendNewNowPlayingMessage(hook, channel, nowPlaying, pausePlay, stop, guildId)
        }
    }

    private fun sendNewNowPlayingMessage(
        hook: InteractionHook,
        channel: Channel?,
        nowPlaying: String,
        pausePlay: Button,
        stop: Button,
        guildId: Long
    ) {
        // Send a new "Now Playing" message and store it
        val nowPlayingMessage = hook.sendMessage(nowPlaying).setActionRow(pausePlay, stop).complete()
        // Store message in the guild's map
        var channelMessageMap = guildLastNowPlayingMessage[guildId]
        if (channelMessageMap == null) {
            channelMessageMap = HashMap()
        }
        channelMessageMap[channel] = nowPlayingMessage
        guildLastNowPlayingMessage[guildId] = channelMessageMap
    }

    fun checkForPlayingTrack(track: AudioTrack?, hook: InteractionHook, deleteDelay: Int?): Boolean {
        if (track == null) {
            hook.sendMessage("There is no track playing currently").setEphemeral(true).queue(
                invokeDeleteOnMessageResponse(
                    deleteDelay!!
                )
            )
            return true
        }
        return false
    }

    fun stopSong(event: IReplyCallback, musicManager: GuildMusicManager, canOverrideSkips: Boolean, deleteDelay: Int?) {
        val hook = event.hook
        if (PlayerManager.instance.isCurrentlyStoppable || canOverrideSkips) {
            val scheduler = musicManager.scheduler
            scheduler.stopTrack(true)
            scheduler.queue.clear()
            scheduler.isLooping = false
            musicManager.audioPlayer.isPaused = false
            hook.deleteOriginal().queue()
            hook.sendMessage("The player has been stopped and the queue has been cleared").queue(
                invokeDeleteOnMessageResponse(
                    deleteDelay!!
                )
            )
            resetNowPlayingMessage(event.guild!!.idLong)
        } else {
            sendDeniedStoppableMessage(hook, musicManager, deleteDelay)
        }
    }

    fun getNowPlayingString(playingTrack: AudioTrack, volume: Int): String {
        val info = playingTrack.info
        if (!info.isStream) {
            val position = playingTrack.position
            val duration = playingTrack.duration
            val songPosition = formatTime(position)
            val songDuration = formatTime(duration)
            return String.format(
                "Now playing `%s` by `%s` `[%s/%s]` (Link: <%s>) with volume '%d'",
                info.title,
                info.author,
                songPosition,
                songDuration,
                info.uri,
                volume
            )
        } else {
            return String.format(
                "Now playing `%s` by `%s` (Link: <%s>) with volume '%d'",
                info.title,
                info.author,
                info.uri,
                volume
            )
        }
    }

    fun changePauseStatusOnTrack(event: IReplyCallback, musicManager: GuildMusicManager, deleteDelay: Int) {
        val audioPlayer = musicManager.audioPlayer
        val paused = audioPlayer.isPaused
        val message = if (paused) "Resuming: `" else "Pausing: `"
        sendMessageAndSetPaused(audioPlayer, event.hook, message, deleteDelay, !paused)
    }

    private fun sendMessageAndSetPaused(
        audioPlayer: AudioPlayer,
        hook: InteractionHook,
        content: String,
        deleteDelay: Int,
        paused: Boolean
    ) {
        val track = audioPlayer.playingTrack
        hook.sendMessage(content)
            .addContent(track.info.title)
            .addContent("` by `")
            .addContent(track.info.author)
            .addContent("`")
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
        audioPlayer.isPaused = paused
    }

    fun skipTracks(
        event: IReplyCallback,
        playerManager: PlayerManager,
        tracksToSkip: Int,
        canOverrideSkips: Boolean,
        deleteDelay: Int?
    ) {
        val hook = event.hook
        val musicManager = playerManager.getMusicManager(event.guild!!)
        val audioPlayer = musicManager.audioPlayer

        if (audioPlayer.playingTrack == null) {
            hook.sendMessage("There is no track playing currently").queue(
                invokeDeleteOnMessageResponse(
                    deleteDelay!!
                )
            )
            return
        }
        if (tracksToSkip < 0) {
            hook.sendMessage("You're not too bright, but thanks for trying").setEphemeral(true).queue(
                invokeDeleteOnMessageResponse(
                    deleteDelay!!
                )
            )
            return
        }

        if (playerManager.isCurrentlyStoppable || canOverrideSkips) {
            for (i in 0 until tracksToSkip) {
                musicManager.scheduler.nextTrack()
            }
            musicManager.scheduler.isLooping = false
            hook.sendMessageFormat("Skipped %d track(s)", tracksToSkip).queue(
                invokeDeleteOnMessageResponse(
                    deleteDelay!!
                )
            )
        } else sendDeniedStoppableMessage(hook, musicManager, deleteDelay)
    }

    fun formatTime(timeInMillis: Long): String {
        val hours = timeInMillis / TimeUnit.HOURS.toMillis(1)
        val minutes = timeInMillis / TimeUnit.MINUTES.toMillis(1)
        val seconds = timeInMillis % TimeUnit.MINUTES.toMillis(1) / TimeUnit.SECONDS.toMillis(1)

        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun adjustTrackPlayingTimes(startTime: Long): Long {
        val adjustmentMap: MutableMap<String, Long> = HashMap()

        if (startTime > 0L) adjustmentMap[MusicDto.Adjustment.START.name] = startTime

        if (adjustmentMap.isEmpty()) {
            return 0L
        }

        if (adjustmentMap.containsKey(MusicDto.Adjustment.START.name)) {
            return adjustmentMap[MusicDto.Adjustment.START.name]!! * SECOND_MULTIPLIER
        }

        //       TODO: return a map when end can be specified too

//        if (adjustmentMap.containsKey(MusicDto.Adjustment.END.name())){
//            return adjustmentMap.get(MusicDto.Adjustment.END.name()) * SECOND_MULTIPLIER;
//        }
        return 0L
    }

    fun isUrl(url: String?): Boolean = runCatching { URI(url) }.isSuccess


    @JvmStatic
    fun deriveDeleteDelayFromTrack(track: AudioTrack): Int {
        return (track.duration / 1000).toInt()
    }

    private fun resetNowPlayingMessage(guildId: Long) {
        val channelMessageMap: Map<Channel?, Message?>? = guildLastNowPlayingMessage[guildId]
        if (!channelMessageMap.isNullOrEmpty()) channelMessageMap.values.forEach {
            it!!.delete().queue()
        }
        guildLastNowPlayingMessage.remove(guildId)
    }


    @JvmStatic
    fun resetMessages(guildId: Long) {
        resetNowPlayingMessage(guildId)
    }

    fun Guild.idString() = idLong.toString()

}
