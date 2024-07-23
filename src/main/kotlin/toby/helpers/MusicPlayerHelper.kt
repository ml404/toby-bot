package toby.helpers

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
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
import java.util.concurrent.TimeUnit

object MusicPlayerHelper {
    private const val webUrl = "https://gibe-toby-bot.herokuapp.com/"
    private const val SECOND_MULTIPLIER = 1000
    val guildLastNowPlayingMessage = ConcurrentHashMap<Long, NowPlayingInfo>()

    class NowPlayingInfo(
        val playerManager: PlayerManager,
        val message: Message
    )

    fun playUserIntro(dbUser: UserDto, guild: Guild, deleteDelay: Int, startPosition: Long = 0L) {
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

        val embed = buildNowPlayingMessageData(track, audioPlayer)
        val (pausePlayButton, stopButton) = generateButtons()
        val guildId = event.guild!!.idLong

        val nowPlayingInfo = guildLastNowPlayingMessage[guildId]

        if (nowPlayingInfo != null) {
            // Update existing message
            nowPlayingInfo.message.editMessageEmbeds(embed)
                .setActionRow(pausePlayButton, stopButton)
                .queue()
            hook.deleteOriginal().queue()
        } else {
            // Send a new message and store it in the map
            hook.sendMessageEmbeds(embed)
                .setActionRow(pausePlayButton, stopButton)
                .queue {
                    guildLastNowPlayingMessage[guildId] = NowPlayingInfo(playerManager, it)
                }
        }
    }

    private fun buildNowPlayingMessageData(track: AudioTrack, audioPlayer: AudioPlayer): MessageEmbed {
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

        val embed = EmbedBuilder()
            .setTitle("Now Playing")
            .setDescription(descriptionBuilder.toString())
            .addField("Volume", "${audioPlayer.volume}", true)
            .setColor(Color.GREEN)
            .setFooter("Link: ${info.uri}", null)
            .addField("Paused?", if (audioPlayer.isPaused) "yes" else "no", false)
            .build()

        return embed
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
            musicManager.audioPlayer.isPaused = false
            hook.deleteOriginal().queue()
            val embed = EmbedBuilder()
                .setTitle("Player Stopped")
                .setDescription("The player has been stopped and the queue has been cleared")
                .setColor(Color.RED)
                .build()

            hook.sendMessageEmbeds(embed)
                .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
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
            .setDescription("$content${track?.info?.title}` by `${track?.info?.author}`")
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
        return (track.duration / SECOND_MULTIPLIER).toInt()
    }

    fun resetMessages(guildId: Long) {
        resetNowPlayingMessage(guildId)
    }

    private fun resetNowPlayingMessage(guildId: Long) {
        val playingInfo = guildLastNowPlayingMessage[guildId]
        playingInfo?.message?.delete()?.queue()
        guildLastNowPlayingMessage.remove(guildId)
    }

    private fun generateButtons(): Pair<Button, Button> {
        val pausePlayButton = Button.primary("pause/play", "⏯️")
        val stopButton = Button.danger("stop", "⏹️")
        return Pair(pausePlayButton, stopButton)
    }
}