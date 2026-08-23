package bot.toby.helpers

import bot.toby.BOT_WEB_URL
import bot.toby.command.commands.music.MusicCommand.Companion.sendDeniedStoppableMessage
import bot.toby.intro.IntroSelection
import bot.toby.lavaplayer.GuildMusicManager
import bot.toby.intro.IntroFailedEvent
import common.intro.IntroHealth
import bot.toby.lavaplayer.PlayerManager
import bot.toby.lavaplayer.SchedulerEvents
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
            // Not the same as having none: the caller only gets here when the
            // user has intros, so a null pick means every one of them is
            // switched off. Saying "no musicDto" sent anyone reading the log
            // looking for a row that was sitting right there.
            logger.info {
                "No intro to play for ${dbUser.discordId}: all ${dbUser.musicDtos.size} of their " +
                    "intros are switched off."
            }
            return null
        }
        return playIntro(selected, guild, event, deleteDelay, startPosition, member, normaliseVolume)
    }

    /**
     * Play one *named* intro, skipping the rotation entirely.
     *
     * Everything below the pick in [playUserIntro], so the two can't drift on
     * volume normalisation or clip handling. The rotation is right when the
     * caller means "their intro" — a voice join, or `/play intro` — and wrong
     * when they have pointed at one: the **View intros** menu shows a member's
     * intros by name, and picking one out of a list only to hear a different
     * one is a strange thing to do to somebody.
     */
    fun playIntro(
        selected: MusicDto,
        guild: Guild,
        event: SlashCommandInteractionEvent? = null,
        deleteDelay: Int,
        startPosition: Long = 0,
        member: Member? = null,
        normaliseVolume: Boolean = true,
    ): MusicDto? {
        logger.setGuildAndMemberContext(guild, member)
        val instance = PlayerManager.instance
        val currentVolume = instance.getMusicManager(guild).audioPlayer.volume

        return runCatching {
            logger.info { "Preparing to play intro '${selected.id}'." }
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
        }.onFailure { error ->
            logger.error("Failed to play intro '${selected.id}': ${error.message}")
            // Anything that throws between picking the intro and handing it to
            // lavaplayer never reaches PlayerManager, so without this it is
            // invisible to the failure counter, the owner's DM and the outage
            // correlation alike — silence with no trace anywhere.
            selected.id?.let {
                SchedulerEvents.publish(IntroFailedEvent(it, IntroHealth.normaliseReason(error.message)))
            }
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
        // Claimed before the send, not after it lands: the message exists on
        // Discord a round-trip before this process hears about it, and a track
        // that dies at once ends inside that window.
        val claim = nowPlayingManager.claimNowPlayingSlot(guildId)
        hook.sendMessageEmbeds(embed)
            .setComponents(ActionRow.of(pausePlayButton, stopButton))
            .queue {
                logger.info("Nowplaying message ${it.idLong} will be stored on guild $guildId")
                nowPlayingManager.setNowPlayingMessage(guildId, it, claim)
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
                clearQueue()
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
        // file_name is nullable and defaults to null, and `!!` here threw an
        // NPE that the caller's runCatching swallowed — the one intro failure
        // that reached none of the health, outage or notification paths,
        // because it never got as far as PlayerManager.
        val fileName = it.fileName.orEmpty()
        // If fileName is a URL, use it directly (backward compatibility)
        if (utilIsUrl(fileName).isNotEmpty()) return fileName
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
