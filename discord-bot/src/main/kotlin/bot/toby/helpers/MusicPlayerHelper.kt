package bot.toby.helpers

import bot.toby.BOT_WEB_URL
import bot.toby.command.commands.music.MusicCommand.Companion.sendDeniedStoppableMessage
import bot.toby.intro.IntroSelection
import bot.toby.lavaplayer.GuildMusicManager
import bot.toby.lavaplayer.PlayerManager
import bot.toby.managers.NowPlayingManager
import bot.toby.util.isUrl as utilIsUrl
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import common.discord.embed
import common.intro.IntroLoudness
import common.logging.DiscordLogger
import common.media.MediaToken
import core.command.Command.Companion.replyEmbedAndDelete
import core.command.Command.Companion.replyEphemeralEmbedAndDelete
import database.dto.music.MusicDto
import database.dto.user.UserDto
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import java.awt.Color

object MusicPlayerHelper {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)
    val nowPlayingManager = NowPlayingManager()

    /**
     * @param lastPlayedIntroId the intro played for this user last time, so
     *        [IntroSelection] can avoid repeating it back to back.
     * @param normaliseVolume apply the intro's measured-loudness correction so
     *        it lands at the same perceived level as everyone else's. Inert
     *        until the intro has played once and been measured, so an existing
     *        install hears no change on the first play of anything.
     * @return the intro that was played, or null when the user has none (or
     *         playback failed) — callers record it as the new "last played".
     */
    fun playUserIntro(
        dbUser: UserDto,
        guild: Guild,
        event: SlashCommandInteractionEvent? = null,
        deleteDelay: Int,
        startPosition: Long = 0,
        member: Member? = null,
        lastPlayedIntroId: String? = null,
        normaliseVolume: Boolean = true,
    ): MusicDto? {
        logger.setGuildAndMemberContext(guild, member)
        logger.info { "Finding intro to play ..." }
        val instance = PlayerManager.instance
        val currentVolume = instance.getMusicManager(guild).audioPlayer.volume

        val selected = IntroSelection.pick(dbUser.musicDtos, lastPlayedIntroId)
        if (selected == null) {
            logger.warn { "User does not have a musicDto. Cannot play intro." }
            return null
        }

        return runCatching {
            logger.info { "User has a musicDto. Preparing to play intro." }
            val nominalVolume = selected.introVolume ?: currentVolume
            val playbackVolume = if (normaliseVolume) {
                IntroLoudness.normalisedVolume(nominalVolume, selected.measuredRms)
            } else {
                nominalVolume
            }
            if (playbackVolume != nominalVolume) {
                logger.info {
                    "Normalising intro '${selected.id}' from $nominalVolume to $playbackVolume " +
                        "(measured rms ${selected.measuredRms})"
                }
            }
            instance.setPreviousVolume(currentVolume)
            val url = determineUrlFromMusicDto(selected)
            val clipStart = selected.startMs?.toLong() ?: startPosition
            val clipEnd = selected.endMs?.toLong()
            logger.info { "Url to play is: '$url' (clip ${clipStart}ms -> ${clipEnd ?: "end"})" }
            instance.loadAndPlayIntro(
                guild, event, url, deleteDelay, clipStart,
                playbackVolume, clipEnd, selected.id,
            )
            selected
        }.onFailure {
            logger.warn { "Failed to play intro '${selected.id}': ${it.message}" }
        }.getOrNull()
    }

    fun nowPlaying(
        event: IReplyCallback,
        playerManager: PlayerManager,
        deleteDelay: Int,
        clipStart: Long? = null,
        clipEnd: Long? = null
    ) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        val musicManager = playerManager.getMusicManager(event.guild!!)
        val audioPlayer = musicManager.audioPlayer
        val track = audioPlayer.playingTrack
        val hook = event.hook

        if (checkForPlayingTrack(track, hook, deleteDelay)) return

        val guild = event.guild!!
        val embed = nowPlayingManager.buildNowPlayingMessageData(
            track, audioPlayer, clipStart, clipEnd, musicManager.scheduler, guild,
        )
        val (pausePlayButton, stopButton) = generateButtons()
        val guildId = guild.idLong
        val nowPlayingInfo = nowPlayingManager.getLastNowPlayingMessage(guildId)

        if (nowPlayingInfo != null) {
            logger.info("Nowplaying message ${nowPlayingInfo.idLong} will be edited on guild $guildId")
            // Update the existing message — but the stored reference can be
            // stale (e.g. the intro's now-playing was deleted on Discord's
            // side), in which case the edit fails. Don't let that silently
            // show nothing: fall back to posting a fresh message (#85).
            nowPlayingInfo.editMessageEmbeds(embed)
                .setComponents(ActionRow.of(pausePlayButton, stopButton))
                .queue(
                    { hook.deleteOriginal().queue() },
                    { error ->
                        logger.warn {
                            "Now-playing message ${nowPlayingInfo.idLong} no longer editable on guild " +
                                "$guildId (${error.message}); posting a fresh one"
                        }
                        sendNewNowPlayingMessage(hook, embed, pausePlayButton, stopButton, guildId)
                    },
                )
        } else {
            sendNewNowPlayingMessage(hook, embed, pausePlayButton, stopButton, guildId)
        }
        nowPlayingManager.scheduleNowPlayingUpdate(
            guildId, track, audioPlayer, 0L, 3L, clipStart, clipEnd, musicManager.scheduler, guild,
        )
    }

    /** Post a brand-new now-playing message and store it as the guild's current one. */
    private fun sendNewNowPlayingMessage(
        hook: InteractionHook,
        embed: MessageEmbed,
        pausePlayButton: Button,
        stopButton: Button,
        guildId: Long,
    ) {
        hook.sendMessageEmbeds(embed)
            .setComponents(ActionRow.of(pausePlayButton, stopButton))
            .queue {
                logger.info("Nowplaying message ${it.idLong} will be stored on guild $guildId")
                nowPlayingManager.setNowPlayingMessage(guildId, it)
            }
    }

    private fun checkForPlayingTrack(track: AudioTrack?, hook: InteractionHook, deleteDelay: Int): Boolean {
        return if (track == null) {
            logger.warn { "No track is currently playing on guild ${hook.interaction.guild?.idLong}.." }
            val noTrackEmbed = embed(
                title = "No Track Playing",
                description = "There is no track playing currently",
                color = Color.RED,
            )
            hook.replyEphemeralEmbedAndDelete(noTrackEmbed, deleteDelay)
            true
        } else {
            false
        }
    }

    fun stopSong(event: IReplyCallback, musicManager: GuildMusicManager, canOverrideSkips: Boolean, deleteDelay: Int) {
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
            hook.replyEmbedAndDelete(
                embed(
                    title = "Player Stopped",
                    description = "The player has been stopped and the queue has been cleared",
                    color = Color.RED,
                ),
                deleteDelay,
            )
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
        hook.replyEmbedAndDelete(
            embed(
                title = "Track Pause/Resume",
                description = "$content${track?.info?.title}` by `${track?.info?.author}`",
                color = Color.CYAN,
            ),
            deleteDelay,
        )
        audioPlayer.isPaused = paused
    }

    fun skipTracks(
        event: IReplyCallback,
        playerManager: PlayerManager,
        tracksToSkip: Int,
        canOverrideSkips: Boolean,
        deleteDelay: Int
    ) {
        val hook = event.hook
        val musicManager = playerManager.getMusicManager(event.guild!!)
        val audioPlayer = musicManager.audioPlayer
        logger.setGuildAndMemberContext(event.guild, event.member)
        when {
            audioPlayer.playingTrack == null -> {
                logger.warn { "Attempted to skip tracks but no track is currently playing ." }
                hook.replyEmbedAndDelete(
                    embed(
                        title = "No Track Playing",
                        description = "There is no track playing currently",
                        color = Color.RED,
                    ),
                    deleteDelay,
                )
                return
            }

            tracksToSkip < 0 -> {
                logger.warn { "Attempted to skip a negative number of tracks: $tracksToSkip ." }
                val invalidSkipEmbed = embed(
                    title = "Invalid Skip Request",
                    description = "You're not too bright, but thanks for trying",
                    color = Color.RED,
                )
                hook.replyEphemeralEmbedAndDelete(invalidSkipEmbed, deleteDelay)
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

            hook.replyEmbedAndDelete(
                embed(
                    title = "Tracks Skipped",
                    description = "Skipped $tracksToSkip track(s)",
                    color = Color.CYAN,
                ),
                deleteDelay,
            )
        } else {
            sendDeniedStoppableMessage(hook, musicManager, deleteDelay)
        }
    }

    private fun determineUrlFromMusicDto(it: MusicDto): String {
        // If fileName is a URL, use it directly (backward compatibility)
        if (utilIsUrl(it.fileName!!).isNotEmpty()) return it.fileName!!
        // If musicBlob contains a URL (e.g. fileName stores the video title), use that
        val blobString = it.musicBlob?.let { bytes -> String(bytes) } ?: ""
        if (utilIsUrl(blobString).isNotEmpty()) return blobString
        // Otherwise, serve the binary data via the web endpoint. Signed: that
        // endpoint has to stay anonymous for lavaplayer to fetch it, and intro
        // ids are guessable, so the signature is what stops it being an open
        // read of every uploaded MP3 in every server.
        return "$BOT_WEB_URL${MediaToken.urlFor(it.id.orEmpty())}"
    }

    fun resetMessages(guildId: Long) = nowPlayingManager.resetNowPlayingMessage(guildId)

    private fun generateButtons(): Pair<Button, Button> {
        val pausePlayButton = Button.primary("pause/play", "⏯️")
        val stopButton = Button.danger("stop", "⏹️")
        return Pair(pausePlayButton, stopButton)
    }
}
