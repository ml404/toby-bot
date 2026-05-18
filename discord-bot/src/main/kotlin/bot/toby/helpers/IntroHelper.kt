package bot.toby.helpers

import bot.toby.handler.EventWaiter
import bot.toby.intro.IntroMediaLoader
import bot.toby.intro.IntroNotificationService
import bot.toby.intro.IntroValidationService
import common.logging.DiscordLogger
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.Command.Companion.replyAndDelete
import core.command.Command.Companion.replyEphemeralAndDelete
import database.dto.ConfigDto
import database.dto.MusicDto
import database.dto.MusicDto.Companion.computeHash
import database.service.ConfigService
import database.service.MusicFileService
import database.service.UserNotificationPrefService
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
import java.net.URI

@Service
class IntroHelper(
    private val userDtoHelper: UserDtoHelper,
    private val musicFileService: MusicFileService,
    private val configService: ConfigService,
    private val httpHelper: HttpHelper,
    private val eventWaiter: EventWaiter,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    // Nullable + default null so legacy positional test constructors keep
    // compiling. Spring autowires the real bean in production.
    private val notificationPrefService: UserNotificationPrefService? = null,
    private val validationService: IntroValidationService = IntroValidationService(httpHelper, dispatcher),
    private val mediaLoader: IntroMediaLoader = IntroMediaLoader(),
    private val notificationService: IntroNotificationService = IntroNotificationService(
        userDtoHelper, musicFileService, httpHelper, eventWaiter, validationService, mediaLoader,
        notificationPrefService, dispatcher,
    ),
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)
    private val supervisorJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(supervisorJob + dispatcher)

    // Per-user pending intro cache, written by /setintro and read by the
    // confirmation menu. Distinct from the DM prompt flow, which lives in
    // IntroNotificationService.
    val pendingIntros = mutableMapOf<Long, Triple<Attachment?, String?, Int>?>()

    fun calculateIntroVolume(event: SlashCommandInteractionEvent): Int {
        val volumePropertyName = ConfigDto.Configurations.INTRO_VOLUME.configValue
        val defaultIntroVolume =
            configService.getConfigByName(volumePropertyName, event.guild?.id)?.value?.toIntOrNull()
        val volumeOption = event.getOption(VOLUME)?.asInt
        return (volumeOption ?: defaultIntroVolume ?: 100).coerceIn(1, 100)
    }

    fun handleMedia(
        event: IReplyCallback,
        requestingUserDto: database.dto.UserDto,
        deleteDelay: Int,
        input: InputData?,
        introVolume: Int,
        selectedMusicDto: MusicDto?,
        userName: String = event.user.effectiveName
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
                handleAttachment(event, requestingUserDto, userName, deleteDelay, input.attachment, introVolume, selectedMusicDto)
            }

            is InputData.Url -> {
                val uriString = input.uri
                if (!URLHelper.isValidURL(uriString)) {
                    event.hook.replyAndDelete("Please provide a valid URL.", deleteDelay)
                    return
                }
                val uri = URI.create(uriString)
                handleUrl(event, requestingUserDto, userName, deleteDelay, uri, introVolume, selectedMusicDto)
            }

            else -> {
                event.hook.replyAndDelete("Please provide a valid link or attachment.", deleteDelay)
            }
        }
    }

    fun handleAttachment(
        event: IReplyCallback,
        requestingUserDto: database.dto.UserDto,
        userName: String,
        deleteDelay: Int,
        attachment: Attachment,
        introVolume: Int,
        selectedMusicDto: MusicDto? = null
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
                    persistMusicFile(event, requestingUserDto, userName, deleteDelay, attachment.fileName, introVolume, it, selectedMusicDto)
                }
            }
        }
    }

    fun handleUrl(
        event: IReplyCallback,
        requestingUserDto: database.dto.UserDto,
        userName: String,
        deleteDelay: Int,
        uri: URI?,
        introVolume: Int,
        selectedMusicDto: MusicDto? = null
    ) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        logger.info { "Handling URL inside intro helper..." }
        val urlString = validationService.convertShortsUrls(uri.toString())
        coroutineScope.launch {
            val title = runCatching { httpHelper.getYouTubeVideoTitle(urlString) }.getOrNull()
            persistMusicUrl(event, requestingUserDto, deleteDelay, title ?: urlString, urlString, userName, introVolume, selectedMusicDto)
        }
    }

    fun findUserById(discordId: Long, guildId: Long) = userDtoHelper.calculateUserDto(guildId, discordId)

    fun findIntroById(musicFileId: String) = musicFileService.getMusicFileById(musicFileId)

    fun updateIntro(musicDto: MusicDto) = musicFileService.updateMusicFile(musicDto)

    fun deleteIntro(musicDto: MusicDto) = musicFileService.deleteMusicFileById(musicDto.id)

    fun downloadAttachment(attachment: Attachment): InputStream? = mediaLoader.downloadAttachment(attachment)

    @VisibleForTesting
    fun persistMusicFile(
        event: IReplyCallback,
        targetDto: database.dto.UserDto,
        userName: String = event.user.effectiveName,
        deleteDelay: Int,
        filename: String,
        introVolume: Int,
        inputStream: InputStream,
        selectedMusicDto: MusicDto? = null
    ) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        logger.info { "Persisting music file" }
        val fileContents = mediaLoader.readContents(inputStream)
            ?: return event.hook.replyEphemeralAndDelete("Unable to read file '$filename'", deleteDelay)

        val index = selectedMusicDto?.index ?: userDtoHelper.calculateUserDto(
            targetDto.discordId,
            targetDto.guildId
        ).musicDtos.size.plus(1)
        val musicDto = selectedMusicDto?.apply {
            this.musicBlob = fileContents
            this.musicBlobHash = computeHash(fileContents)
            this.fileName = filename
            this.introVolume = introVolume
            this.userDto = targetDto
        } ?: MusicDto(targetDto, index, filename, introVolume, fileContents)

        if (selectedMusicDto == null) {
            musicFileService.createNewMusicFile(musicDto)
                ?.let { sendSuccessMessage(event, userName, filename, introVolume, index, deleteDelay) }
                ?: rejectIntroForDuplication(event, userName, filename, deleteDelay)
        } else {
            musicFileService.updateMusicFile(musicDto)
                ?.let { sendUpdateMessage(event, userName, filename, introVolume, musicDto.index!!, deleteDelay) }
                ?: rejectIntroForDuplication(event, userName, filename, deleteDelay)
        }
    }

    fun persistMusicUrl(
        event: IReplyCallback,
        targetDto: database.dto.UserDto,
        deleteDelay: Int,
        filename: String,
        url: String,
        memberName: String,
        introVolume: Int,
        selectedMusicDto: MusicDto? = null
    ) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        logger.info { "Persisting music URL for user '$memberName' on guild: ${event.guild?.idLong}" }
        val urlBytes = url.toByteArray()
        val index = selectedMusicDto?.index ?: userDtoHelper.calculateUserDto(
            targetDto.discordId,
            targetDto.guildId
        ).musicDtos.size.plus(1)
        val musicDto = selectedMusicDto?.apply {
            this.id = "${targetDto.guildId}_${targetDto.discordId}_$index"
            this.userDto = targetDto
            this.musicBlob = urlBytes
            this.musicBlobHash = computeHash(urlBytes)
            this.fileName = filename
            this.introVolume = introVolume
        } ?: MusicDto(targetDto, index, filename, introVolume, urlBytes)

        if (selectedMusicDto == null) {
            logger.info { "Creating new music file $musicDto" }
            musicFileService.createNewMusicFile(musicDto)
                ?.let { sendSuccessMessage(event, memberName, filename, introVolume, index, deleteDelay) }
                ?: rejectIntroForDuplication(event, memberName, filename, deleteDelay)
        } else {
            logger.info { "Updating music file $musicDto" }
            musicFileService.updateMusicFile(musicDto)
                ?.let { sendUpdateMessage(event, memberName, filename, introVolume, musicDto.index!!, deleteDelay) }
                ?: rejectIntroForDuplication(event, memberName, filename, deleteDelay)
        }
    }

    private fun sendSuccessMessage(
        event: IReplyCallback,
        memberName: String,
        filename: String,
        introVolume: Int,
        index: Int,
        deleteDelay: Int
    ) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        logger.info { "Successfully set $memberName's intro song #${index} to '$filename' with volume '$introVolume'" }
        event.hook.replyEphemeralAndDelete(
            "Successfully set $memberName's intro song #${index} to '$filename' with volume '$introVolume'",
            deleteDelay,
        )
    }

    private fun sendUpdateMessage(
        event: IReplyCallback,
        memberName: String,
        filename: String,
        introVolume: Int,
        index: Int,
        deleteDelay: Int
    ) {
        event.hook.replyEphemeralAndDelete(
            "Successfully updated $memberName's intro song #${index} to '$filename' with volume '$introVolume'",
            deleteDelay,
        )
    }

    private fun rejectIntroForDuplication(
        event: IReplyCallback,
        memberName: String,
        filename: String,
        deleteDelay: Int
    ) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        logger.info { "$memberName's intro song '$filename' was rejected for duplication" }
        event.hook.replyEphemeralAndDelete(
            "$memberName's intro song '$filename' was rejected as it already exists as one of their intros for this server",
            deleteDelay,
        )
    }

    /**
     * Public delegations preserved so existing callers/tests don't have to
     * be rewired in the same change.
     */
    fun promptUserForMusicInfo(user: User, guild: Guild) =
        notificationService.promptUserForMusicInfo(user, guild)

    @VisibleForTesting
    fun saveUserMusicDto(user: User, guild: Guild, inputData: InputData, volume: Int?, displayName: String? = null) =
        notificationService.saveUserMusicDto(user, guild, inputData, volume, displayName)

    @VisibleForTesting
    fun determineMusicBlob(input: InputData): ByteArray? = notificationService.determineMusicBlob(input)

    @VisibleForTesting
    fun determineFileName(input: InputData): String = notificationService.determineFileName(input)

    fun validateIntroLength(url: String, onResult: (Boolean) -> Unit) =
        validationService.validateIntroLength(url, onResult)

    suspend fun checkForOverlyLongIntroDuration(url: String): Boolean =
        validationService.checkForOverlyLongIntroDuration(url)

    fun parseVolume(content: String): Int? = validationService.parseVolume(content)

    companion object {
        private const val VOLUME = "volume"

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
