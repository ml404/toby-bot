package bot.toby.helpers

import bot.logging.DiscordLogger
import bot.toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import bot.toby.handler.EventWaiter
import bot.toby.helpers.FileUtils.computeHash
import bot.toby.helpers.MusicPlayerHelper.isUrl
import database.dto.ConfigDto
import database.dto.MusicDto
import database.dto.UserDto
import database.service.IConfigService
import database.service.IMusicFileService
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.Message.Attachment
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import org.jetbrains.annotations.VisibleForTesting
import org.springframework.stereotype.Service
import java.io.InputStream
import java.net.URI
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit

@Service
class IntroHelper(
    private val userDtoHelper: UserDtoHelper,
    private val musicFileService: IMusicFileService,
    private val configService: IConfigService,
    private val eventWaiter: EventWaiter
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
        selectedMusicDto: MusicDto?,
        userName: String = event.user.effectiveName
    ) {
        logger.setGuildAndMemberContext(event.guild, event.member)
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
        logger.setGuildAndMemberContext(event.guild, event.member)
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
        logger.setGuildAndMemberContext(event.guild, event.member)
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

    fun findUserById(discordId: Long, guildId: Long) = userDtoHelper.calculateUserDto(guildId, discordId)

    fun findIntroById(musicFileId: String) = musicFileService.getMusicFileById(musicFileId)

    fun updateIntro(musicDto: MusicDto) = musicFileService.updateMusicFile(musicDto)

    private fun createIntro(musicDto: MusicDto) = musicFileService.createNewMusicFile(musicDto)

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
        logger.setGuildAndMemberContext(event.guild, event.member)
        logger.info { "Persisting music file" }
        val fileContents = getFileContents(inputStream)
            ?: return event.hook.sendMessageFormat("Unable to read file '%s'", filename)
                .setEphemeral(true)
                .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))

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

    private fun getFileContents(inputStream: InputStream) =
        runCatching { FileUtils.readInputStreamToByteArray(inputStream) }.getOrNull()

    fun persistMusicUrl(
        event: IReplyCallback,
        targetDto: UserDto,
        deleteDelay: Int?,
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
        deleteDelay: Int?
    ) {
        logger.setGuildAndMemberContext(event.guild, event.member)
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
        logger.setGuildAndMemberContext(event.guild, event.member)
        logger.info { "$memberName's intro song '$filename' was rejected for duplication" }
        event.hook
            .sendMessage("$memberName's intro song '$filename' was rejected as it already exists as one of their intros for this server")
            .setEphemeral(true)
            .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
    }

    fun promptUserForMusicInfo(user: User, guild: Guild) {
        logger.setGuildAndUserContext(guild, user)
        logger.info { "Prompting user to set an intro for the server that they don't have one on" }
        user.openPrivateChannel().queue { channel ->
            channel.sendMessage("You don't have an intro song yet on server '${guild.name}'! Please reply with a YouTube URL or upload a music file, and optionally provide a volume level (1-100). E.g. 'https://www.youtube.com/watch?v=VIDEO_ID_HERE 90'")
                .queue(invokeDeleteOnMessageResponse(5.minutes.toInt(DurationUnit.SECONDS)))

            // Wait for a response in the user's DM
            eventWaiter.waitForMessage(
                { event -> event.author.idLong == user.idLong && event.channel == channel },
                { event ->
                    handleUserMusicResponse(event, guild)
                },
                5.minutes,
                {
                    logger.info { "User did not set intro for the server that they don't have one on within the timeout period" }
                    channel.sendMessage("You didn't respond in time, you can always use the '/setintro' command on server '${guild.name}'")
                        .queue(invokeDeleteOnMessageResponse(5.minutes.toInt(DurationUnit.SECONDS)))
                }
            )
        }
    }

    private fun handleUserMusicResponse(event: MessageReceivedEvent, guild: Guild) {
        val message = event.message
        val content = message.contentRaw
        val attachment = message.attachments.firstOrNull()

        val (inputData, volume) = if (attachment != null) {
            // User uploaded a file, treat it as a music file
            val inputData = InputData.Attachment(attachment)
            logger.info("User uploaded a music file: $inputData")
            inputData to parseVolume(content)
        } else if (isUrl(extractUrl(content))) {
            // User sent a URL
            logger.info("User provided a URL: $content")
            InputData.Url(extractUrl(content)) to parseVolume(content)
        } else {
            event.channel.sendMessage("Please provide a valid URL or upload a file.").queue()
            return
        }
        saveUserMusicDto(event.author, guild, inputData, volume)
    }


    fun parseVolume(content: String): Int? {
        // Try to extract a volume value (0-100) from the message content
        val volumeRegex = """\b(\d{1,3})\b""".toRegex()
        val volumeMatch = volumeRegex.find(content)
        return volumeMatch?.value?.toIntOrNull()?.takeIf { it in 0..100 }
    }

    // Method to extract URL using regex
    private fun extractUrl(content: String): String {
        // Regex pattern to match a URL
        val urlRegex = Regex(
            """\b(https?://[^\s/$.?#].\S*)\b""",
            RegexOption.IGNORE_CASE
        )
        return urlRegex.find(content)?.value ?: ""
    }

    @VisibleForTesting
    fun saveUserMusicDto(user: User, guild: Guild, inputData: InputData, volume: Int?) {
        // Save the musicDto for the user with the provided URL and volume
        logger.info("Constructing musicDto from input ...")
        val requestingUserDto = userDtoHelper.calculateUserDto(user.idLong, guild.idLong)
        // Logic to save the musicDto in the database
        val musicDto = MusicDto(requestingUserDto, 1, determineFileName(inputData), volume ?: 90, determineMusicBlob(inputData))
        createIntro(musicDto)
        logger.info { "User successfully uploaded intro as a result of the prompt!" }
        user.openPrivateChannel().queue { channel ->
            channel.sendMessage("Successfully set your intro on server '${guild.name}'")
                .queue(invokeDeleteOnMessageResponse(1.minutes.toInt(DurationUnit.SECONDS)))
        }
    }

    @VisibleForTesting
    fun determineMusicBlob(input: InputData): ByteArray? {
        return when (input) {
            is InputData.Url -> {
                logger.info("Processing URL input...")
                // Logic for handling URL, e.g., downloading content, converting to blob, etc.
                input.uri.toByteArray()  // Convert the URL to a ByteArray
            }

            is InputData.Attachment -> {
                logger.info("Processing attachment input...")
                val inputStream = downloadAttachment(input.attachment)
                inputStream?.let { getFileContents(it) }  // Get the file contents as ByteArray if the input stream isn't null
            }
        }
    }

    @VisibleForTesting
    fun determineFileName(input: InputData): String {
        return when (input) {
            is InputData.Url -> {
                // Ensure the URL is not null or empty
                input.uri.takeIf { it.isNotBlank() } ?: "" // Provide a default name if the URL is null or blank
            }

            is InputData.Attachment -> {
                // Check if the attachment or its fileName is null
                input.attachment.fileName
            }
        }
    }


    companion object {
        private const val VOLUME = "volume"
    }
}

sealed class InputData {
    data class Attachment(val attachment: Message.Attachment) : InputData()
    data class Url(val uri: String) : InputData()
}