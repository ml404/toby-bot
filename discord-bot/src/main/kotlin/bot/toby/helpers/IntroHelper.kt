package bot.toby.helpers

import bot.toby.intro.IntroMediaLoader
import bot.toby.intro.IntroNotificationService
import bot.toby.intro.IntroOwnership
import bot.toby.intro.IntroValidationService
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import common.intro.IntroClip
import common.intro.IntroSlots
import common.logging.DiscordLogger
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.Command.Companion.replyAndDelete
import core.command.Command.Companion.replyEphemeralAndDelete
import database.dto.guild.ConfigDto
import database.dto.music.MusicDto
import database.dto.music.MusicDto.Companion.computeHash
import database.service.guild.ConfigService
import database.service.music.MusicFileService
import database.service.user.UserNotificationPrefService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.Message.Attachment
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import org.jetbrains.annotations.VisibleForTesting
import org.springframework.stereotype.Service
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.net.URI

@Service
class IntroHelper(
    private val userDtoHelper: UserDtoHelper,
    private val musicFileService: MusicFileService,
    private val configService: ConfigService,
    private val httpHelper: HttpHelper,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    // Nullable + default null so legacy positional test constructors keep
    // compiling. Spring autowires the real bean in production.
    private val notificationPrefService: UserNotificationPrefService? = null,
    private val validationService: IntroValidationService = IntroValidationService(httpHelper, dispatcher),
    private val mediaLoader: IntroMediaLoader = IntroMediaLoader(),
    private val notificationService: IntroNotificationService =
        IntroNotificationService(notificationPrefService),
    // Injectable so the pending-intro expiry is testable without waiting a
    // quarter of an hour. Production uses the system clock.
    pendingTicker: Ticker = Ticker.systemTicker(),
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)
    private val supervisorJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(supervisorJob + dispatcher)

    /**
     * Intros accepted from a user but not yet saved, because something still
     * has to come back from them — which slot to replace, or the volume and
     * clip form the message context menu opens.
     *
     * This was a bare `mutableMapOf`, which was wrong twice over. It's written
     * from JDA's dispatch threads and from the coroutine the modal listener
     * launches, and a `LinkedHashMap` under concurrent writes can corrupt into
     * a lookup that never returns. And only the replace menu's success path
     * ever removed an entry, so every abandoned flow leaked one — each pinning
     * a [Message.Attachment] — with nothing to ever clear it. Closing a modal
     * without submitting is an entirely ordinary thing to do, so the context
     * menu made that leak easy to hit.
     *
     * Caffeine gives both: thread safety, and entries that expire on their own.
     * The rest of this package already stores short-lived state this way
     * ([bot.toby.intro.IntroUndoStore], [bot.toby.intro.IntroPlaybackTracker]).
     *
     * Keyed by guild *and* user, so a form opened in one server can't be
     * answered with a pick made in another.
     */
    private val pendingIntros = Caffeine.newBuilder()
        .expireAfterWrite(PENDING_INTRO_TTL_MINUTES, TimeUnit.MINUTES)
        .maximumSize(1_000)
        .ticker(pendingTicker)
        .build<String, PendingIntro>()

    private fun pendingKey(guildId: Long, discordId: Long) = "$guildId:$discordId"

    /** Hold [pending] until the user tells us where it goes. */
    fun parkPendingIntro(guildId: Long, discordId: Long, pending: PendingIntro) {
        pendingIntros.put(pendingKey(guildId, discordId), pending)
    }

    /** The intro this user is part-way through setting, if it hasn't expired. */
    fun pendingIntro(guildId: Long, discordId: Long): PendingIntro? =
        pendingIntros.getIfPresent(pendingKey(guildId, discordId))

    /** Drop a pending intro once it has been saved, or abandoned deliberately. */
    fun clearPendingIntro(guildId: Long, discordId: Long) {
        pendingIntros.invalidate(pendingKey(guildId, discordId))
    }

    fun calculateIntroVolume(event: SlashCommandInteractionEvent): Int =
        resolveIntroVolume(event.guild?.id, event.getOption(VOLUME)?.asInt)

    /**
     * The guild's default intro volume, for entry points that have no volume
     * option to offer — currently the message context menu.
     */
    fun defaultIntroVolume(guildId: String?): Int = resolveIntroVolume(guildId, null)

    private fun resolveIntroVolume(guildId: String?, requested: Int?): Int {
        val volumePropertyName = ConfigDto.Configurations.INTRO_VOLUME.configValue
        val defaultIntroVolume = configService.getConfigByName(volumePropertyName, guildId)?.value?.toIntOrNull()
        return (requested ?: defaultIntroVolume ?: 100).coerceIn(1, 100)
    }

    fun handleMedia(
        event: IReplyCallback,
        requestingUserDto: database.dto.user.UserDto,
        deleteDelay: Int,
        input: InputData?,
        introVolume: Int,
        selectedMusicDto: MusicDto?,
        userName: String = event.user.effectiveName,
        startMs: Int? = null,
        endMs: Int? = null
    ) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        logger.info { "Handling media inside intro helper ..." }

        when (input) {
            is InputData.Attachment -> {
                if (!validationService.isValidAttachment(input.attachment)) {
                    event.hook.replyAndDelete(
                        "Please provide a valid mp3 file under ${IntroValidationService.MAX_FILE_SIZE_KB}kb.",
                        deleteDelay,
                    )
                    return
                }
                handleAttachment(
                    event, requestingUserDto, userName, deleteDelay, input.attachment,
                    introVolume, selectedMusicDto, startMs, endMs
                )
            }

            is InputData.Url -> {
                val uriString = input.uri
                if (!URLHelper.isValidURL(uriString)) {
                    event.hook.replyAndDelete("Please provide a valid URL.", deleteDelay)
                    return
                }
                val uri = URI.create(uriString)
                handleUrl(
                    event, requestingUserDto, userName, deleteDelay, uri,
                    introVolume, selectedMusicDto, startMs, endMs
                )
            }

            else -> {
                event.hook.replyAndDelete("Please provide a valid link or attachment.", deleteDelay)
            }
        }
    }

    fun handleAttachment(
        event: IReplyCallback,
        requestingUserDto: database.dto.user.UserDto,
        userName: String,
        deleteDelay: Int,
        attachment: Attachment,
        introVolume: Int,
        selectedMusicDto: MusicDto? = null,
        startMs: Int? = null,
        endMs: Int? = null
    ) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        logger.info { "Handling attachment inside intro helper..." }
        when {
            attachment.fileExtension != "mp3" -> {
                logger.info { "Invalid file extension used" }
                event.hook.replyAndDelete("Please use mp3 files only", deleteDelay)
            }

            attachment.size > IntroValidationService.MAX_FILE_SIZE -> {
                logger.info { "File size was too large" }
                event.hook.replyAndDelete(
                    "Please keep the file size under ${IntroValidationService.MAX_FILE_SIZE_KB}kb",
                    deleteDelay,
                )
            }

            else -> {
                val inputStream = downloadAttachment(attachment)
                inputStream?.let {
                    persistMusicFile(
                        event, requestingUserDto, userName, deleteDelay, attachment.fileName,
                        introVolume, it, selectedMusicDto, startMs, endMs
                    )
                }
            }
        }
    }

    /**
     * @param knownTitle the track's title when the caller already has it —
     *        `/setintro current` reads it off the loaded track. Skips the
     *        YouTube lookup, which is both a network round trip and, for a
     *        SoundCloud or Bandcamp source, one that was never going to
     *        answer.
     */
    fun handleUrl(
        event: IReplyCallback,
        requestingUserDto: database.dto.user.UserDto,
        userName: String,
        deleteDelay: Int,
        uri: URI?,
        introVolume: Int,
        selectedMusicDto: MusicDto? = null,
        startMs: Int? = null,
        endMs: Int? = null,
        knownTitle: String? = null
    ) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        logger.info { "Handling URL inside intro helper..." }
        val urlString = validationService.convertShortsUrls(uri.toString())
        val supplied = knownTitle?.trim()?.takeIf { it.isNotEmpty() }
        coroutineScope.launch {
            val title = supplied ?: runCatching { httpHelper.getYouTubeVideoTitle(urlString) }.getOrNull()
            persistMusicUrl(
                event, requestingUserDto, deleteDelay, title ?: urlString, urlString,
                userName, introVolume, selectedMusicDto, startMs, endMs
            )
        }
    }

    // NB: (discordId, guildId), matching UserDtoHelper.calculateUserDto and
    // every other call site. This used to forward the pair reversed, so the
    // super-user path in `/setintro users:@someone` resolved — and lazily
    // created — a row keyed (guildId, discordId), whose intros could never be
    // found again by the normal lookup.
    fun findUserById(discordId: Long, guildId: Long) = userDtoHelper.calculateUserDto(discordId, guildId)

    fun findIntroById(musicFileId: String) = musicFileService.getMusicFileById(musicFileId)

    /**
     * Whether [actorDiscordId] may edit or delete the intro [introId] in this
     * guild: their own always, anyone's if they're a super-user.
     *
     * The super-user lookup only runs when the intro isn't theirs, so the
     * ordinary path stays a string comparison.
     */
    fun canManageIntro(introId: String?, guildId: Long, actorDiscordId: Long): Boolean =
        IntroOwnership.ownedBy(introId, guildId, actorDiscordId) ||
            findUserById(actorDiscordId, guildId).superUser

    fun createIntro(musicDto: MusicDto) = musicFileService.createNewMusicFile(musicDto)

    fun updateIntro(musicDto: MusicDto) = musicFileService.updateMusicFile(musicDto)

    fun deleteIntro(musicDto: MusicDto) = musicFileService.deleteMusicFileById(musicDto.id)

    fun downloadAttachment(attachment: Attachment): InputStream? = mediaLoader.downloadAttachment(attachment)

    @VisibleForTesting
    fun persistMusicFile(
        event: IReplyCallback,
        targetDto: database.dto.user.UserDto,
        userName: String = event.user.effectiveName,
        deleteDelay: Int,
        filename: String,
        introVolume: Int,
        inputStream: InputStream,
        selectedMusicDto: MusicDto? = null,
        startMs: Int? = null,
        endMs: Int? = null
    ) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        logger.info { "Persisting music file" }
        val fileContents = mediaLoader.readContents(inputStream)
            ?: return event.hook.replyEphemeralAndDelete("Unable to read file '$filename'", deleteDelay)

        val index = selectedMusicDto?.let { slotOf(it) } ?: allocateSlot(targetDto)
            ?: return rejectIntroForFullSlots(event, deleteDelay)
        val musicDto = selectedMusicDto?.apply {
            this.index = index
            this.musicBlob = fileContents
            this.musicBlobHash = computeHash(fileContents)
            this.fileName = filename
            this.introVolume = introVolume
            this.userDto = targetDto
            this.startMs = startMs
            this.endMs = endMs
        } ?: MusicDto(targetDto, index, filename, introVolume, fileContents, startMs, endMs)

        if (selectedMusicDto == null) {
            musicFileService.createNewMusicFile(musicDto)
                ?.let { sendSuccessMessage(event, userName, filename, introVolume, index, deleteDelay, startMs, endMs) }
                ?: rejectIntroSave(event, userName, filename, deleteDelay, musicDto)
        } else {
            musicFileService.updateMusicFile(musicDto)
                ?.let {
                    sendUpdateMessage(
                        event, userName, filename, introVolume, musicDto.index!!, deleteDelay, startMs, endMs
                    )
                }
                ?: rejectIntroSave(event, userName, filename, deleteDelay)
        }
    }

    fun persistMusicUrl(
        event: IReplyCallback,
        targetDto: database.dto.user.UserDto,
        deleteDelay: Int,
        filename: String,
        url: String,
        memberName: String,
        introVolume: Int,
        selectedMusicDto: MusicDto? = null,
        startMs: Int? = null,
        endMs: Int? = null
    ) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        logger.info { "Persisting music URL for user '$memberName' on guild: ${event.guild?.idLong}" }
        val urlBytes = url.toByteArray()
        val index = selectedMusicDto?.let { slotOf(it) } ?: allocateSlot(targetDto)
            ?: return rejectIntroForFullSlots(event, deleteDelay)
        val musicDto = selectedMusicDto?.apply {
            // Deliberately *not* reassigning `id`. It is the primary key, and
            // rewriting it made `merge` insert a second row and orphan the
            // original whenever `index` had drifted from the id — which the
            // web drag-reorder causes, since it rewrites index only.
            this.index = index
            this.userDto = targetDto
            this.musicBlob = urlBytes
            this.musicBlobHash = computeHash(urlBytes)
            this.fileName = filename
            this.introVolume = introVolume
            this.startMs = startMs
            this.endMs = endMs
        } ?: MusicDto(targetDto, index, filename, introVolume, urlBytes, startMs, endMs)

        if (selectedMusicDto == null) {
            logger.info { "Creating new music file $musicDto" }
            musicFileService.createNewMusicFile(musicDto)
                ?.let { sendSuccessMessage(event, memberName, filename, introVolume, index, deleteDelay, startMs, endMs) }
                ?: rejectIntroSave(event, memberName, filename, deleteDelay, musicDto)
        } else {
            logger.info { "Updating music file $musicDto" }
            musicFileService.updateMusicFile(musicDto)
                ?.let {
                    sendUpdateMessage(
                        event, memberName, filename, introVolume, musicDto.index!!, deleteDelay, startMs, endMs
                    )
                }
                ?: rejectIntroSave(event, memberName, filename, deleteDelay)
        }
    }

    /**
     * The slot a brand-new intro should take, re-reading the user so the count
     * reflects anything saved since the command started.
     *
     * Allocates against the existing **ids**, which is what a new row can
     * collide with — see [IntroSlots.nextFreeSlot] for why reading the `index`
     * column silently overwrote people's intros.
     *
     * @return the free slot, or null when all [IntroSlots.MAX_INTRO_COUNT] are taken.
     */
    /**
     * The slot an existing intro occupies, read from its id rather than its
     * `index` column. The two can disagree — the web drag-reorder rewrites
     * `index` and can't rewrite the id — and the id is the one that decides
     * which row a write lands on, so it wins.
     */
    private fun slotOf(intro: MusicDto): Int? = IntroSlots.slotFromId(intro.id) ?: intro.index

    private fun allocateSlot(targetDto: database.dto.user.UserDto): Int? {
        val existing = userDtoHelper.calculateUserDto(targetDto.discordId, targetDto.guildId).musicDtos
        return IntroSlots.nextFreeSlot(existing.map { it.id })
    }

    private fun rejectIntroForFullSlots(event: IReplyCallback, deleteDelay: Int) {
        logger.info { "All ${IntroSlots.MAX_INTRO_COUNT} intro slots are taken; nothing saved" }
        event.hook.replyEphemeralAndDelete(
            "You already have ${IntroSlots.MAX_INTRO_COUNT} intros — " +
                "delete one with `/deleteintro`, or use `/setintro` to pick which to replace.",
            deleteDelay,
        )
    }

    /**
     * Trailing " (clip 0:03 – 0:12)" for a clipped intro, empty otherwise, so
     * the confirmation echoes exactly what will play back on join.
     */
    private fun clipSuffix(startMs: Int?, endMs: Int?): String =
        if (startMs == null && endMs == null) "" else " (clip ${IntroClip.describe(startMs, endMs)})"

    private fun sendSuccessMessage(
        event: IReplyCallback,
        memberName: String,
        filename: String,
        introVolume: Int,
        index: Int,
        deleteDelay: Int,
        startMs: Int? = null,
        endMs: Int? = null
    ) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        val message = "Successfully set $memberName's intro song #${index} to '$filename' " +
            "with volume '$introVolume'${clipSuffix(startMs, endMs)}"
        logger.info { message }
        event.hook.replyEphemeralAndDelete(message, deleteDelay)
    }

    private fun sendUpdateMessage(
        event: IReplyCallback,
        memberName: String,
        filename: String,
        introVolume: Int,
        index: Int,
        deleteDelay: Int,
        startMs: Int? = null,
        endMs: Int? = null
    ) {
        event.hook.replyEphemeralAndDelete(
            "Successfully updated $memberName's intro song #${index} to '$filename' " +
                "with volume '$introVolume'${clipSuffix(startMs, endMs)}",
            deleteDelay,
        )
    }

    /**
     * Explain a save that didn't happen.
     *
     * `createNewMusicFile` returns null for two different reasons, and this
     * used to report both as a duplicate. Since the persistence layer started
     * refusing an already-occupied slot rather than merging over it, "rejected
     * as it already exists as one of their intros" could be shown to someone
     * whose audio was nowhere in their list — leaving them to work out what
     * happened from a message that wasn't true.
     */
    private fun rejectIntroSave(
        event: IReplyCallback,
        memberName: String,
        filename: String,
        deleteDelay: Int,
        attempted: MusicDto? = null
    ) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        val duplicate = attempted?.let { musicFileService.isFileAlreadyUploaded(it) }
        if (duplicate == null) {
            // Not already uploaded, so the row was refused for the only other
            // reason the persistence layer refuses one: something is already
            // in that slot. The allocator is supposed to make this
            // unreachable, so it's worth a loud log as well as an honest
            // message.
            logger.error(
                "Could not save $memberName's intro '$filename': the target slot " +
                    "(${attempted?.id ?: "unknown"}) was already taken."
            )
            event.hook.replyEphemeralAndDelete(
                "Couldn't save '$filename' — the slot it was going into is already in use. " +
                    "Check `/listintros`, then use `/setintro` to pick which intro to replace.",
                deleteDelay,
            )
            return
        }
        logger.info { "$memberName's intro song '$filename' was rejected for duplication" }
        // Naming the slot turns "that's already there" into something the user
        // can act on — it's the slot they'd pass to /setintro to replace.
        val where = duplicate.index?.let { " in slot #$it" }.orEmpty()
        event.hook.replyEphemeralAndDelete(
            "$memberName's intro song '$filename' was rejected as it already exists$where " +
                "as one of their intros for this server",
            deleteDelay,
        )
    }

    fun promptUserForMusicInfo(user: User, guild: Guild) =
        notificationService.promptUserForMusicInfo(user, guild)

    fun validateIntroLength(url: String, onResult: (Boolean) -> Unit) =
        validationService.validateIntroLength(url, onResult)

    /**
     * Clip-aware length check. Replaces the plain [validateIntroLength] on the
     * `/setintro` path so a source longer than the cap is accepted when the
     * user supplies a start/end inside it — matching the web form.
     */
    fun validateClipAgainstSource(url: String?, startMs: Int?, endMs: Int?, onResult: (String?) -> Unit) =
        validationService.validateClipAgainstSource(url, startMs, endMs, onResult)

    suspend fun checkForOverlyLongIntroDuration(url: String): Boolean =
        validationService.checkForOverlyLongIntroDuration(url)

    fun parseVolume(content: String): Int? = validationService.parseVolume(content)

    companion object {
        private const val VOLUME = "volume"

        /**
         * How long a half-finished intro is held for.
         *
         * Matches Discord's 15-minute interaction lifetime: past that the
         * replace menu or clip form can't be answered anyway, so holding the
         * payload longer only keeps an attachment reference alive. Both
         * readers already explain themselves when there's nothing waiting.
         */
        const val PENDING_INTRO_TTL_MINUTES = 15L

        // Backwards-compat aliases — canonical values live on
        // [IntroValidationService.Companion]. Existing callers (SetIntroCommand,
        // tests) still see the same constants here.
        const val MAX_FILE_SIZE = IntroValidationService.MAX_FILE_SIZE
        const val MAX_FILE_SIZE_KB = IntroValidationService.MAX_FILE_SIZE_KB
        val INTRO_LIMIT = IntroValidationService.INTRO_LIMIT
    }
}

sealed class InputData {
    data class Attachment(val attachment: Message.Attachment) : InputData()
    data class Url(val uri: String) : InputData()
}

/**
 * An intro `/setintro` has accepted but can't save yet, because the user is at
 * their slot limit and still has to choose which existing intro to replace.
 * Held by [IntroHelper.parkPendingIntro] until [bot.toby.menu.menus.SetIntroMenu]
 * resolves the choice.
 */
data class PendingIntro(
    val attachment: Message.Attachment?,
    val url: String?,
    val volume: Int,
    val startMs: Int? = null,
    val endMs: Int? = null,
)
