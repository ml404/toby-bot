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
    private const val SECOND_MULTIPLIER = 1000
    private val guildLastNowPlayingMessage = mutableMapOf<Long, MutableMap<Channel?, Message?>>()

    fun playUserIntro(dbUser: UserDto, guild: Guild?, deleteDelay: Int, startPosition: Long?) {
        playUserIntro(dbUser, guild, null, deleteDelay, startPosition)
    }

    fun playUserIntro(
        dbUser: UserDto,
        guild: Guild?,
        event: SlashCommandInteractionEvent?,
        deleteDelay: Int,
        startPosition: Long?
    ) {
        val musicDto = dbUser.musicDto
        val instance = PlayerManager.instance
        val currentVolume = instance.getMusicManager(guild!!).audioPlayer.volume

        musicDto?.let {
            val introVolume = it.introVolume
            instance.setPreviousVolume(currentVolume)
            val url = if (it.fileName != null) "$webUrl/music?id=${it.id}" else it.musicBlob.contentToString()
            instance.loadAndPlay(guild, event, url, true, deleteDelay, startPosition!!, introVolume)
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

        val channelMessageMap = guildLastNowPlayingMessage[guildId]

        try {
            if (channelMessageMap == null || channelMessageMap[channel] == null) {
                sendNewNowPlayingMessage(hook, channel, nowPlaying, pausePlay, stop, guildId)
            } else {
                val updatedNowPlaying = getNowPlayingString(track, volume)
                channelMessageMap.values.forEach { message ->
                    message?.editMessage(updatedNowPlaying)?.setActionRow(pausePlay, stop)?.queue()
                }
                hook.deleteOriginal().queue()
            }
        } catch (e: IllegalArgumentException) {
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
        val nowPlayingMessage = hook.sendMessage(nowPlaying).setActionRow(pausePlay, stop).complete()
        val channelMessageMap = guildLastNowPlayingMessage.getOrPut(guildId) { mutableMapOf() }
        channelMessageMap[channel] = nowPlayingMessage
    }

    private fun checkForPlayingTrack(track: AudioTrack?, hook: InteractionHook, deleteDelay: Int?): Boolean {
        return if (track == null) {
            hook.sendMessage("There is no track playing currently").setEphemeral(true).queue(
                invokeDeleteOnMessageResponse(deleteDelay ?: 0)
            )
            true
        } else {
            false
        }
    }

    fun stopSong(event: IReplyCallback, musicManager: GuildMusicManager, canOverrideSkips: Boolean, deleteDelay: Int?) {
        val hook = event.hook
        if (PlayerManager.instance.isCurrentlyStoppable || canOverrideSkips) {
            musicManager.scheduler.apply {
                stopTrack(true)
                queue.clear()
                isLooping = false
            }
            musicManager.audioPlayer.isPaused = false
            hook.deleteOriginal().queue()
            hook.sendMessage("The player has been stopped and the queue has been cleared").queue(
                invokeDeleteOnMessageResponse(deleteDelay ?: 0)
            )
            resetNowPlayingMessage(event.guild!!.idLong)
        } else {
            sendDeniedStoppableMessage(hook, musicManager, deleteDelay)
        }
    }

    private fun getNowPlayingString(playingTrack: AudioTrack, volume: Int): String {
        val info = playingTrack.info
        return if (!info.isStream) {
            val songPosition = formatTime(playingTrack.position)
            val songDuration = formatTime(playingTrack.duration)
            "Now playing `${info.title}` by `${info.author}` `[$songPosition/$songDuration]` (Link: <${info.uri}>) with volume '$volume'"
        } else {
            "Now playing `${info.title}` by `${info.author}` (Link: <${info.uri}>) with volume '$volume'"
        }
    }

    fun changePauseStatusOnTrack(event: IReplyCallback, musicManager: GuildMusicManager, deleteDelay: Int) {
        val audioPlayer = musicManager.audioPlayer
        val paused = audioPlayer.isPaused
        val message = if (paused) "Resuming: `" else "Pausing: `"
        sendMessageAndSetPaused(audioPlayer, event, message, deleteDelay, !paused)
    }

    private fun sendMessageAndSetPaused(
        audioPlayer: AudioPlayer,
        event: IReplyCallback,
        content: String,
        deleteDelay: Int,
        paused: Boolean
    ) {
        val track = audioPlayer.playingTrack
        event.hook
            .sendMessage("$content${track.info.title}` by `${track.info.author}`")
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

        when {
            audioPlayer.playingTrack == null -> {
                hook.sendMessage("There is no track playing currently").queue(
                    invokeDeleteOnMessageResponse(deleteDelay ?: 0)
                )
                return
            }
            tracksToSkip < 0 -> {
                hook.sendMessage("You're not too bright, but thanks for trying").setEphemeral(true)
                    .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0)
                )
                return
            }
        }

        if (playerManager.isCurrentlyStoppable || canOverrideSkips) {
            repeat(tracksToSkip) {
                musicManager.scheduler.nextTrack()
            }
            musicManager.scheduler.isLooping = false
            hook.sendMessage("Skipped $tracksToSkip track(s)").queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
        } else {
            sendDeniedStoppableMessage(hook, musicManager, deleteDelay)
        }
    }

    fun formatTime(timeInMillis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(timeInMillis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(timeInMillis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(timeInMillis) % 60

        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun adjustTrackPlayingTimes(startTime: Long): Long {
        val adjustmentMap = mutableMapOf<String, Long>()
        if (startTime > 0L) adjustmentMap[MusicDto.Adjustment.START.name] = startTime
        return adjustmentMap[MusicDto.Adjustment.START.name]?.times(SECOND_MULTIPLIER) ?: 0L
    }

    fun isUrl(url: String?): Boolean = runCatching {
        if (url != null) {
            URI(url)
        }
    }.isSuccess

    @JvmStatic
    fun deriveDeleteDelayFromTrack(track: AudioTrack): Int {
        return (track.duration / 1000).toInt()
    }

    private fun resetNowPlayingMessage(guildId: Long) {
        guildLastNowPlayingMessage[guildId]?.values?.forEach { it?.delete()?.queue() }
        guildLastNowPlayingMessage.remove(guildId)
    }

    @JvmStatic
    fun resetMessages(guildId: Long) {
        resetNowPlayingMessage(guildId)
    }
}
