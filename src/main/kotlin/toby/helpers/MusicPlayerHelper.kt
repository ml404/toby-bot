package toby.helpers

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import net.dv8tion.jda.api.interactions.components.buttons.Button
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.command.commands.music.IMusicCommand.Companion.sendDeniedStoppableMessage
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.lavaplayer.GuildMusicManager
import toby.lavaplayer.PlayerManager
import java.awt.Color
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

data class PlayerContext(val playerManager: PlayerManager, val hook: InteractionHook)

object MusicPlayerHelper {
    private const val webUrl = "https://gibe-toby-bot.herokuapp.com/"
    private const val SECOND_MULTIPLIER = 1000
    private var scheduler: ScheduledExecutorService? = null
    private val guildPlayerContextMap = ConcurrentHashMap<Long, PlayerContext>()

    fun playUserIntro(dbUser: UserDto, guild: Guild, deleteDelay: Int, startPosition: Long) {
        playUserIntro(dbUser, guild, null, deleteDelay, startPosition)
    }

    fun playUserIntro(
        dbUser: UserDto,
        guild: Guild,
        event: SlashCommandInteractionEvent?,
        deleteDelay: Int,
        startPosition: Long
    ) {
        val musicDto = dbUser.musicDto
        val instance = PlayerManager.instance
        val currentVolume = instance.getMusicManager(guild).audioPlayer.volume

        musicDto?.let {
            val introVolume = it.introVolume
            instance.setPreviousVolume(currentVolume)
            val url = if (it.fileName != null) "$webUrl/music?id=${it.id}" else it.musicBlob.contentToString()
            instance.loadAndPlay(guild, event, url, true, deleteDelay, startPosition, introVolume ?: currentVolume)
        }
    }

    fun nowPlaying(event: IReplyCallback, playerManager: PlayerManager, deleteDelay: Int?) {
        val musicManager = playerManager.getMusicManager(event.guild!!)
        val audioPlayer = musicManager.audioPlayer
        val track = audioPlayer.playingTrack
        val hook = event.hook
        if (checkForPlayingTrack(track, hook, deleteDelay)) return

        // Check if a hook already exists in the map
        val existingContext = guildPlayerContextMap[event.guild!!.idLong]

        if (existingContext != null) {
            // If the hook already exists, update the existing message instead of sending a new one
            val embed = buildNowPlayingEmbed(track, audioPlayer)
            val (pausePlayButton, stopButton) = generateButtons()

            existingContext.hook.editOriginalEmbeds(embed)
                .setActionRow(pausePlayButton, stopButton)
                .queue()

        } else {
            val embed = buildNowPlayingEmbed(track, audioPlayer)
            val (pausePlayButton, stopButton) = generateButtons()

            hook.sendMessageEmbeds(embed)
                .setActionRow(pausePlayButton, stopButton)
                .queue()

            val guildId = hook.interaction.guild!!.idLong
            guildPlayerContextMap[guildId] = PlayerContext(playerManager, hook)

            startNowPlayingUpdater(playerManager, hook, audioPlayer)
        }
    }

    private fun buildNowPlayingEmbed(track: AudioTrack, audioPlayer: AudioPlayer): MessageEmbed {
        val info = track.info
        val descriptionBuilder = StringBuilder()

        descriptionBuilder.append("**Title**: `${info.title}`\n").append("**Author**: `${info.author}`\n")

        if (!info.isStream) {
            val songPosition = formatTime(track.position)
            val songDuration = formatTime(track.duration)
            descriptionBuilder.append("**Progress**: `$songPosition / $songDuration`\n")
        } else {
            descriptionBuilder.append("**Stream**: `Live`\n")
        }

        return EmbedBuilder()
            .setTitle("Now Playing")
            .setDescription(descriptionBuilder.toString())
            .addField("Volume", "${audioPlayer.volume}", true)
            .setColor(Color.GREEN)
            .setFooter("Link: ${info.uri}", null)
            .addField("Paused?", if (audioPlayer.isPaused) "yes" else "no", false)
            .build()
    }

    private fun startNowPlayingUpdater(playerManager: PlayerManager, hook: InteractionHook, audioPlayer: AudioPlayer) {
        scheduler = Executors.newScheduledThreadPool(1)
        if (scheduler == null || scheduler?.isShutdown == true) {
            scheduler = Executors.newScheduledThreadPool(1)
            val initialDelay = 5L // initial delay before first update (in seconds)
            val updateInterval = 5L // interval between updates (in seconds)
            scheduler?.scheduleAtFixedRate({
                val updatedTrack = audioPlayer.playingTrack // Get the latest track information
                if (updatedTrack != null) {
                    updateNowPlayingMessage(playerManager, updatedTrack, hook, audioPlayer) // Update the embed
                }
            }, initialDelay, updateInterval, TimeUnit.SECONDS)
        }
    }

    private fun updateNowPlayingMessage(
        playerManager: PlayerManager,
        track: AudioTrack,
        hook: InteractionHook,
        audioPlayer: AudioPlayer
    ) {
        val guildId = hook.interaction.guild!!.idLong
        val embed = buildNowPlayingEmbed(track, audioPlayer)
        val (pausePlayButton, stopButton) = generateButtons()

        hook.editOriginalEmbeds(embed)
            .setActionRow(pausePlayButton, stopButton)
            .queue()

        guildPlayerContextMap[guildId] = PlayerContext(playerManager, hook)
    }

    private fun checkForPlayingTrack(track: AudioTrack?, hook: InteractionHook, deleteDelay: Int?): Boolean {
        return if (track == null) {
            val embed = EmbedBuilder()
                .setTitle("No Track Playing")
                .setDescription("There is no track playing currently")
                .setColor(Color.RED)
                .build()

            hook.sendMessageEmbeds(embed).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
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
            stopScheduler()
            musicManager.audioPlayer.isPaused = false
            hook.deleteOriginal().queue()
            val embed = EmbedBuilder()
                .setTitle("Player Stopped")
                .setDescription("The player has been stopped and the queue has been cleared")
                .setColor(Color.RED)
                .build()

            hook.sendMessageEmbeds(embed).queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
            resetMessages(event.guild!!.idLong)
        } else {
            sendDeniedStoppableMessage(hook, musicManager, deleteDelay)
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
        val hook = event.hook
        val embed = EmbedBuilder()
            .setTitle("Track Pause/Resume")
            .setDescription("$content${track.info.title}` by `${track.info.author}`")
            .setColor(Color.CYAN)
            .build()

        hook.sendMessageEmbeds(embed).queue(invokeDeleteOnMessageResponse(deleteDelay))
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
                val embed = EmbedBuilder()
                    .setTitle("No Track Playing")
                    .setDescription("There is no track playing currently")
                    .setColor(Color.RED)
                    .build()

                hook.sendMessageEmbeds(embed).queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
                return
            }

            tracksToSkip < 0 -> {
                val embed = EmbedBuilder()
                    .setTitle("Invalid Skip Request")
                    .setDescription("You're not too bright, but thanks for trying")
                    .setColor(Color.RED)
                    .build()

                hook.sendMessageEmbeds(embed).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
                return
            }
        }

        if (playerManager.isCurrentlyStoppable || canOverrideSkips) {
            repeat(tracksToSkip) {
                musicManager.scheduler.nextTrack()
            }
            musicManager.scheduler.isLooping = false

            val embed = EmbedBuilder()
                .setTitle("Tracks Skipped")
                .setDescription("Skipped $tracksToSkip track(s)")
                .setColor(Color.CYAN)
                .build()

            hook.sendMessageEmbeds(embed).queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
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

    fun resetMessages(guildId: Long) {
        guildPlayerContextMap[guildId]?.hook?.deleteOriginal()
        guildPlayerContextMap.remove(guildId)
    }

    private fun generateButtons(): Pair<Button, Button> {
        val pausePlayButton = Button.primary("pause_play", "Pause/Play")
        val stopButton = Button.danger("stop", "Stop")
        return Pair(pausePlayButton, stopButton)
    }

    private fun stopScheduler() {
        scheduler?.shutdown()
        runCatching {
            if (!scheduler?.awaitTermination(1, TimeUnit.SECONDS)!!) {
                scheduler?.shutdownNow()
            }
        }.onFailure {
            scheduler?.shutdownNow()
        }
    }
}
