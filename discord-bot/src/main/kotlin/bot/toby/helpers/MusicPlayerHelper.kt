package bot.toby.helpers

import bot.database.dto.MusicDto
import bot.database.dto.UserDto
import bot.logging.DiscordLogger
import bot.toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import bot.toby.command.commands.music.IMusicCommand.Companion.sendDeniedStoppableMessage
import bot.toby.lavaplayer.GuildMusicManager
import bot.toby.lavaplayer.PlayerManager
import bot.toby.managers.NowPlayingManager
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import net.dv8tion.jda.api.interactions.components.buttons.Button
import java.awt.Color
import java.util.concurrent.TimeUnit

object MusicPlayerHelper {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)
    private const val WEB_URL = "https://gibe-toby-bot.herokuapp.com"
    private const val SECOND_MULTIPLIER = 1000
    val nowPlayingManager = NowPlayingManager()

    fun playUserIntro(
        dbUser: UserDto,
        guild: Guild,
        event: SlashCommandInteractionEvent? = null,
        deleteDelay: Int,
        startPosition: Long = 0,
        member: Member? = null
    ) {
        logger.setGuildAndMemberContext(guild, member)
        logger.info { "Finding intro to play ..." }
        val musicDto = dbUser.musicDtos
        val instance = PlayerManager.instance
        val currentVolume = instance.getMusicManager(guild).audioPlayer.volume

        runCatching {
            musicDto.random().let {
                logger.info { "User has a musicDto. Preparing to play intro." }
                val introVolume = it.introVolume
                instance.setPreviousVolume(currentVolume)
                val url = determineUrlFromMusicDto(it)
                logger.info { "Url to play is: '$url'" }
                instance.loadAndPlay(guild, event, url, true, deleteDelay, startPosition, introVolume ?: currentVolume)
            }
        }.onFailure {
            logger.warn { "User does not have a musicDto. Cannot play intro." }
        }
    }

    fun nowPlaying(event: IReplyCallback, playerManager: PlayerManager, deleteDelay: Int?) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        val musicManager = playerManager.getMusicManager(event.guild!!)
        val audioPlayer = musicManager.audioPlayer
        val track = audioPlayer.playingTrack
        val hook = event.hook

        if (checkForPlayingTrack(track, hook, deleteDelay)) return

        val embed = nowPlayingManager.buildNowPlayingMessageData(track, audioPlayer)
        val (pausePlayButton, stopButton) = generateButtons()
        val guildId = event.guild!!.idLong
        val nowPlayingInfo = nowPlayingManager.getLastNowPlayingMessage(guildId)

        if (nowPlayingInfo != null) {
            logger.info("Nowplaying message ${nowPlayingInfo.idLong} will be edited on guild $guildId")
            // Update existing message
            nowPlayingInfo.editMessageEmbeds(embed)
                .setActionRow(pausePlayButton, stopButton)
                .queue()
            hook.deleteOriginal().queue()
        } else {
            // Send a new message and store it in the map
            hook.sendMessageEmbeds(embed)
                .setActionRow(pausePlayButton, stopButton)
                .queue {
                    logger.info("Nowplaying message ${it.idLong} will be stored on guild $guildId")
                    nowPlayingManager.setNowPlayingMessage(guildId, it)
                }
        }
        nowPlayingManager.scheduleNowPlayingUpdate(guildId, track, audioPlayer, 0L, 3L)
    }

    private fun checkForPlayingTrack(track: AudioTrack?, hook: InteractionHook, deleteDelay: Int?): Boolean {
        return if (track == null) {
            logger.warn { "No track is currently playing on guild ${hook.interaction.guild?.idLong}.." }
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
        logger.setGuildAndMemberContext(event.guild, event.member)
        val hook = event.hook
        if (PlayerManager.instance.isCurrentlyStoppable || canOverrideSkips) {
            logger.info { "Stopping the song and clearing the queue." }
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
        logger.setGuildAndMemberContext(event.guild, event.member)
        val audioPlayer = musicManager.audioPlayer
        val paused = audioPlayer.isPaused
        val message = if (paused) "Resuming: `" else "Pausing: `"
        logger.info { "Changing pause status to ${!paused} for track ${audioPlayer.playingTrack?.info?.title} ." }
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
        logger.setGuildAndMemberContext(event.guild, event.member)
        when {
            audioPlayer.playingTrack == null -> {
                logger.warn { "Attempted to skip tracks but no track is currently playing ." }
                val embed = EmbedBuilder()
                    .setTitle("No Track Playing")
                    .setDescription("There is no track playing currently")
                    .setColor(Color.RED)
                    .build()

                hook.sendMessageEmbeds(embed).queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
                return
            }

            tracksToSkip < 0 -> {
                logger.warn { "Attempted to skip a negative number of tracks: $tracksToSkip ." }
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
            logger.info { "Skipping $tracksToSkip track(s)." }
            nowPlayingManager.cancelScheduledTask(event.guild?.idLong!!)
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

    private fun determineUrlFromMusicDto(it: MusicDto): String =
        if (isUrl(it.fileName!!).isNotEmpty()) {
            // It's a URL, return it directly
            it.fileName!!
        } else {
            // It's an MP3 file, return the local URL serving the binary data
            "$WEB_URL/music?id=${it.id}"
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

    // Method to extract URL using regex
    fun isUrl(content: String): String {
        // Regex pattern to match a URL
        val urlRegex = Regex(
            """\b(https?://[^\s/$.?#].\S*)\b""",
            RegexOption.IGNORE_CASE
        )
        return urlRegex.find(content)?.value ?: ""
    }

    @JvmStatic
    fun deriveDeleteDelayFromTrack(track: AudioTrack): Int {
        return (track.duration / SECOND_MULTIPLIER).toInt()
    }

    fun resetMessages(guildId: Long) = nowPlayingManager.resetNowPlayingMessage(guildId)

    private fun generateButtons(): Pair<Button, Button> {
        val pausePlayButton = Button.primary("pause/play", "⏯️")
        val stopButton = Button.danger("stop", "⏹️")
        return Pair(pausePlayButton, stopButton)
    }
}
