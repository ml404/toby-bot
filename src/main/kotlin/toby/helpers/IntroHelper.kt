package toby.helpers

import net.dv8tion.jda.api.entities.Message.Attachment
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import org.springframework.stereotype.Service
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IConfigService
import toby.jpa.service.IMusicFileService
import toby.jpa.service.IUserService
import java.io.InputStream
import java.net.URI
import java.util.*

@Service
class IntroHelper(
    private val userService: IUserService,
    private val musicFileService: IMusicFileService,
    private val configService: IConfigService
) {

    // Store the pending intro in a cache
    val pendingIntros = mutableMapOf<Long, Pair<Attachment, Int>?>()


    fun calculateIntroVolume(event: SlashCommandInteractionEvent): Int {
        val volumePropertyName = ConfigDto.Configurations.VOLUME.configValue
        val defaultVolume = configService.getConfigByName(volumePropertyName, event.guild?.id)?.value?.toIntOrNull()
        val volumeOption = event.getOption(VOLUME)?.asInt
        return (volumeOption ?: defaultVolume ?: 100).coerceIn(1, 100)
    }

    fun handleMedia(
        event: SlashCommandInteractionEvent,
        requestingUserDto: UserDto,
        deleteDelay: Int?,
        attachment: Attachment?,
        link: String?,
        introVolume: Int,
        selectedMusicDto: MusicDto?
    ) {
        when {
            attachment != null -> handleAttachment(
                event,
                requestingUserDto,
                deleteDelay,
                attachment,
                introVolume,
                selectedMusicDto
            )
            link != null && URLHelper.isValidURL(link) -> handleUrl(
                event,
                requestingUserDto,
                deleteDelay,
                Optional.of(URI.create(link)),
                introVolume,
                selectedMusicDto
            )
            else -> event.hook.sendMessage("Please provide a valid link or attachment")
                .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        }
    }

    fun handleAttachment(
        event: SlashCommandInteractionEvent,
        requestingUserDto: UserDto,
        deleteDelay: Int?,
        attachment: Attachment,
        introVolume: Int,
        selectedMusicDto: MusicDto? = MusicDto()
    ) {
        when {
            attachment.fileExtension != "mp3" -> {
                event.hook.sendMessage("Please use mp3 files only")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            }

            attachment.size > 400000 -> {
                event.hook.sendMessage("Please keep the file size under 400kb")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            }

            else -> {
                val inputStream = downloadAttachment(attachment)
                inputStream?.let {
                    persistMusicFile(
                        event,
                        requestingUserDto,
                        deleteDelay,
                        attachment.fileName,
                        introVolume,
                        it,
                        selectedMusicDto
                    )
                }
            }
        }
    }

    fun handleUrl(
        event: SlashCommandInteractionEvent,
        requestingUserDto: UserDto,
        deleteDelay: Int?,
        optionalURI: Optional<URI>,
        introVolume: Int,
        selectedMusicDto: MusicDto? = MusicDto()
    ) {
        val urlString = optionalURI.map(URI::toString).orElse("")
        persistMusicUrl(
            event,
            requestingUserDto,
            deleteDelay,
            urlString,
            urlString,
            event.user.name,
            introVolume,
            selectedMusicDto
        )
    }

    private fun downloadAttachment(attachment: Attachment): InputStream? {
        return runCatching {
            attachment.proxy.download().get()
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    private fun persistMusicFile(
        event: SlashCommandInteractionEvent,
        targetDto: UserDto,
        deleteDelay: Int?,
        filename: String,
        introVolume: Int,
        inputStream: InputStream,
        selectedMusicDto: MusicDto?
    ) {
        val fileContents = runCatching { FileUtils.readInputStreamToByteArray(inputStream) }.getOrNull()
            ?: return event.hook.sendMessageFormat("Unable to read file '%s'", filename)
                .setEphemeral(true)
                .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))

        val index = targetDto.musicDtos.size + 1
        val musicDto = selectedMusicDto ?: MusicDto(
            targetDto.discordId,
            targetDto.guildId,
            index,
            filename,
            introVolume,
            fileContents
        )
        musicDto.apply {
            this.fileName = filename
            this.introVolume = introVolume
            this.musicBlob = fileContents
            this.userDto = targetDto
        }

        if (selectedMusicDto == null) {
            musicFileService.createNewMusicFile(musicDto)
            targetDto.musicDtos[index] = musicDto
            userService.updateUser(targetDto)
            sendSuccessMessage(event, event.user.effectiveName, filename, introVolume, deleteDelay)
        } else {
            musicFileService.updateMusicFile(musicDto)
            sendUpdateMessage(event, event.user.effectiveName, filename, introVolume, deleteDelay)
        }
    }

    private fun persistMusicUrl(
        event: SlashCommandInteractionEvent,
        targetDto: UserDto,
        deleteDelay: Int?,
        filename: String,
        url: String,
        memberName: String,
        introVolume: Int,
        selectedMusicDto: MusicDto?
    ) {
        val urlBytes = url.toByteArray()
        val index = targetDto.musicDtos.size + 1
        val musicDto = selectedMusicDto ?: MusicDto(targetDto.discordId, targetDto.guildId, index, filename, introVolume, urlBytes)
        musicDto.apply {
            this.fileName = filename
            this.introVolume = introVolume
            this.musicBlob = urlBytes
            this.userDto = targetDto
        }


        if (selectedMusicDto == null) {
            musicFileService.createNewMusicFile(musicDto)
            targetDto.musicDtos[index] = musicDto
            userService.updateUser(targetDto)
            sendSuccessMessage(event, memberName, filename, introVolume, deleteDelay)
        } else {
            musicFileService.updateMusicFile(musicDto)
            sendUpdateMessage(event, memberName, filename, introVolume, deleteDelay)
        }
    }

    private fun sendSuccessMessage(
        event: SlashCommandInteractionEvent,
        memberName: String,
        filename: String,
        introVolume: Int,
        deleteDelay: Int?
    ) {
        event.hook
            .sendMessage("Successfully set $memberName's intro song to '$filename' with volume '$introVolume'")
            .setEphemeral(true)
            .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
    }

    private fun sendUpdateMessage(
        event: SlashCommandInteractionEvent,
        memberName: String?,
        filename: String,
        introVolume: Int,
        deleteDelay: Int?
    ) {
        event.hook
            .sendMessage("Successfully updated $memberName's intro song to '$filename' with volume '$introVolume'")
            .setEphemeral(true)
            .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
    }

    companion object {
        private const val VOLUME = "volume"
    }
}
