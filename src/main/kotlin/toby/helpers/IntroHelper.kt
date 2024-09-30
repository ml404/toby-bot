package toby.helpers

import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.Message.Attachment
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import org.jetbrains.annotations.VisibleForTesting
import org.springframework.stereotype.Service
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.helpers.FileUtils.computeHash
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IConfigService
import toby.jpa.service.IMusicFileService
import toby.logging.DiscordLogger
import java.io.InputStream
import java.net.URI

@Service
class IntroHelper(
    private val userDtoHelper: UserDtoHelper,
    private val musicFileService: IMusicFileService,
    private val configService: IConfigService
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    // Store the pending intro in a cache (as either an attachment or a URL string)
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
        requestingUserDto: UserDto,
        deleteDelay: Int?,
        input: InputData?,
        introVolume: Int,
        selectedMusicDto: MusicDto? = null,
        userName: String = event.user.effectiveName
    ) {
        logger.setGuildAndUserContext(event.guild, event.member)
        logger.info { "Handling media inside intro helper ..." }

        when (input) {
            is InputData.Attachment -> {
                // Validate the attachment before proceeding
                if (!isValidAttachment(input.attachment)) {
                    event.hook.sendMessage("Please provide a valid mp3 file under 400kb.")
                        .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                    return
                }
                handleAttachment(
                    event,
                    requestingUserDto,
                    userName,
                    deleteDelay,
                    input.attachment,
                    introVolume,
                    selectedMusicDto
                )
            }

            is InputData.Url -> {
                val uriString = input.uri
                if (!URLHelper.isValidURL(uriString)) {
                    event.hook.sendMessage("Please provide a valid URL.")
                        .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                    return
                }
                val uri = URI.create(uriString)
                handleUrl(
                    event,
                    requestingUserDto,
                    userName,
                    deleteDelay,
                    uri,
                    introVolume,
                    selectedMusicDto
                )
            }
            else -> {
                event.hook.sendMessage("Please provide a valid link or attachment.")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            }
        }
    }

    private fun isValidAttachment(attachment: Attachment): Boolean {
        return attachment.fileExtension == "mp3" && attachment.size <= 400000 // Max size in bytes
    }


    fun handleAttachment(
        event: IReplyCallback,
        requestingUserDto: UserDto,
        userName: String,
        deleteDelay: Int?,
        attachment: Attachment,
        introVolume: Int,
        selectedMusicDto: MusicDto? = null
    ) {
        logger.setGuildAndUserContext(event.guild, event.member)
        logger.info { "Handling attachment inside intro helper..." }
        when {
            attachment.fileExtension != "mp3" -> {
                logger.info { "Invalid file extension used" }
                event.hook.sendMessage("Please use mp3 files only")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            }

            attachment.size > 400000 -> {
                logger.info { "File size was too large" }
                event.hook.sendMessage("Please keep the file size under 400kb")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            }

            else -> {
                val inputStream = downloadAttachment(attachment)
                inputStream?.let {
                    persistMusicFile(
                        event,
                        requestingUserDto,
                        userName,
                        deleteDelay,
                        attachment.fileName,
                        introVolume,
                        it,
                        selectedMusicDto,
                    )
                }
            }
        }
    }

    fun handleUrl(
        event: IReplyCallback,
        requestingUserDto: UserDto,
        userName: String,
        deleteDelay: Int?,
        uri: URI?,
        introVolume: Int,
        selectedMusicDto: MusicDto? = null
    ) {
        logger.setGuildAndUserContext(event.guild, event.member)
        logger.info { "Handling URL inside intro helper..." }
        val urlString = uri.toString()
        persistMusicUrl(
            event,
            requestingUserDto,
            deleteDelay,
            urlString,
            urlString,
            userName,
            introVolume,
            selectedMusicDto
        )
    }

    fun findUserById(discordId: Long, guildId: Long) = userDtoHelper.calculateUserDto(discordId, guildId)

    fun findIntroById(musicFileId: String) = musicFileService.getMusicFileById(musicFileId)

    fun updateIntro(musicDto: MusicDto) = musicFileService.updateMusicFile(musicDto)

    fun createIntro(musicDto: MusicDto) = musicFileService.createNewMusicFile(musicDto)

    fun downloadAttachment(attachment: Attachment): InputStream? {
        return runCatching {
            attachment.proxy.download().get()
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    @VisibleForTesting
    fun persistMusicFile(
        event: IReplyCallback,
        targetDto: UserDto,
        userName: String = event.user.effectiveName,
        deleteDelay: Int?,
        filename: String,
        introVolume: Int,
        inputStream: InputStream,
        selectedMusicDto: MusicDto? = null
    ) {
        logger.setGuildAndUserContext(event.guild, event.member)
        logger.info { "Persisting music file" }
        val fileContents = runCatching { FileUtils.readInputStreamToByteArray(inputStream) }.getOrNull()
            ?: return event.hook.sendMessageFormat("Unable to read file '%s'", filename)
                .setEphemeral(true)
                .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))

        val index = userDtoHelper.calculateUserDto(targetDto.guildId, targetDto.discordId).musicDtos.size.plus(1)
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
        targetDto: UserDto,
        deleteDelay: Int?,
        filename: String,
        url: String,
        memberName: String,
        introVolume: Int,
        selectedMusicDto: MusicDto?
    ) {
        logger.setGuildAndUserContext(event.guild, event.member)
        logger.info { "Persisting music URL for user '$memberName' on guild: ${event.guild?.idLong}" }
        val urlBytes = url.toByteArray()
        val index = userDtoHelper.calculateUserDto(targetDto.guildId, targetDto.discordId).musicDtos.size.plus(1)
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
        deleteDelay: Int?
    ) {
        logger.setGuildAndUserContext(event.guild, event.member)
        logger.info { "Successfully set $memberName's intro song #${index} to '$filename' with volume '$introVolume'" }
        event.hook
            .sendMessage("Successfully set $memberName's intro song #${index} to '$filename' with volume '$introVolume'")
            .setEphemeral(true)
            .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
    }

    private fun sendUpdateMessage(
        event: IReplyCallback,
        memberName: String,
        filename: String,
        introVolume: Int,
        index: Int,
        deleteDelay: Int?
    ) {
        event.hook
            .sendMessage("Successfully updated $memberName's intro song #${index} to '$filename' with volume '$introVolume'")
            .setEphemeral(true)
            .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
    }

    private fun rejectIntroForDuplication(
        event: IReplyCallback,
        memberName: String,
        filename: String,
        deleteDelay: Int?
    ) {
        logger.setGuildAndUserContext(event.guild, event.member)
        logger.info { "$memberName's intro song '$filename' was rejected for duplication" }
        event.hook
            .sendMessage("$memberName's intro song '$filename' was rejected as it already exists as one of their intros for this server")
            .setEphemeral(true)
            .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
    }

    companion object {
        private const val VOLUME = "volume"
    }
}

sealed class InputData {
    data class Attachment(val attachment: Message.Attachment) : InputData()
    data class Url(val uri: String) : InputData()
}
