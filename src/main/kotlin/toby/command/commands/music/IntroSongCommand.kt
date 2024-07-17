package toby.command.commands.music

import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.helpers.FileUtils
import toby.helpers.URLHelper
import toby.helpers.UserDtoHelper
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IConfigService
import toby.jpa.service.IMusicFileService
import toby.jpa.service.IUserService
import toby.lavaplayer.PlayerManager
import java.io.InputStream
import java.net.URI
import java.util.*

class IntroSongCommand(
    private val userService: IUserService,
    private val musicFileService: IMusicFileService,
    private val configService: IConfigService
) : IMusicCommand {

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(
        ctx: CommandContext, instance: PlayerManager, requestingUserDto: UserDto, deleteDelay: Int?
    ) {
        val event = ctx.event
        event.deferReply().queue()

        val introVolume = calculateIntroVolume(event)

        if (!requestingUserDto.superUser && event.getMentionedMembers().isNotEmpty()) {
            sendErrorMessage(event, deleteDelay!!)
            return
        }

        val attachmentOption = event.getOption(ATTACHMENT)
        val linkOption = event.getOption(LINK)?.asString.orEmpty()

        when {

            linkOption.isNotEmpty() && URLHelper.isValidURL(linkOption) -> {
                val optionalURI = URLHelper.fromUrlString(linkOption)
                setIntroViaUrl(event, requestingUserDto, deleteDelay, optionalURI, introVolume)
                return
            }

            attachmentOption?.asAttachment != null -> {
                setIntroViaDiscordAttachment(
                    event,
                    requestingUserDto,
                    deleteDelay,
                    attachmentOption.asAttachment,
                    introVolume
                )
                return
            }

            else -> {
                event.hook.sendMessage("Please provide a valid link or attachment")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            }
        }
    }

    private fun calculateIntroVolume(event: SlashCommandInteractionEvent): Int {
        val volumePropertyName = ConfigDto.Configurations.VOLUME.configValue
        val defaultVolume = configService.getConfigByName(volumePropertyName, event.guild?.id)?.value?.toIntOrNull()
        val volumeOption = event.getOption(VOLUME)?.asInt
        val introVolume = volumeOption ?: defaultVolume ?: 100
        return introVolume.coerceIn(1, 100)
    }

    private fun SlashCommandInteractionEvent.getMentionedMembers(): List<Member> {
        return this.getOption(USERS)?.mentions?.members.orEmpty()
    }

    private fun setIntroViaDiscordAttachment(
        event: SlashCommandInteractionEvent,
        requestingUserDto: UserDto?,
        deleteDelay: Int?,
        attachment: Message.Attachment,
        introVolume: Int
    ) {
        when {
            attachment.fileExtension != "mp3" -> {
                event.hook.sendMessage("Please use mp3 files only").queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            }

            attachment.size > 400000 -> {
                event.hook.sendMessage("Please keep the file size under 400kb")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            }

            else -> {
                val inputStream = downloadAttachment(attachment)
                if (inputStream != null) {
                    handleMusicFile(
                        event,
                        requestingUserDto,
                        deleteDelay,
                        attachment.fileName,
                        introVolume,
                        inputStream
                    )
                }
            }
        }
    }

    private fun downloadAttachment(attachment: Message.Attachment): InputStream? {
        return runCatching {
            attachment.proxy.download().get()
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    private fun setIntroViaUrl(
        event: SlashCommandInteractionEvent,
        requestingUserDto: UserDto?,
        deleteDelay: Int?,
        optionalURI: Optional<URI>,
        introVolume: Int
    ) {
        val urlString = optionalURI.map(URI::toString).orElse("")
        val mentionedMembers = event.getMentionedMembers()
        if (requestingUserDto?.superUser == true && mentionedMembers.isNotEmpty()) {
            handleMusicUrl(event, mentionedMembers, deleteDelay, urlString, introVolume)
        } else {
            requestingUserDto?.let {
                persistMusicUrl(event, it, deleteDelay, urlString, urlString, event.user.name, introVolume)
            }
        }
    }

    private fun handleMusicFile(
        event: SlashCommandInteractionEvent,
        requestingUserDto: UserDto?,
        deleteDelay: Int?,
        filename: String,
        introVolume: Int,
        inputStream: InputStream
    ) {
        // Return early if requestingUserDto is null
        requestingUserDto ?: return

        val mentionedMembers = event.getMentionedMembers()

        // Check if the requesting user is a superuser and there are mentioned members
        if (requestingUserDto.superUser && mentionedMembers.isNotEmpty()) {
            setMusicForMentionedUsers(event, mentionedMembers, deleteDelay, filename, introVolume, inputStream)
        } else {
            setMusicFileForUser(event, requestingUserDto, deleteDelay, filename, introVolume, inputStream)
        }
    }

    private fun setMusicForMentionedUsers(
        event: SlashCommandInteractionEvent,
        mentionedMembers: List<Member>,
        deleteDelay: Int?,
        filename: String,
        introVolume: Int,
        inputStream: InputStream
    ) {
        mentionedMembers.forEach { member ->
            UserDtoHelper.calculateUserDto(
                member.guild.idLong,
                member.idLong,
                member.isOwner,
                userService,
                introVolume
            ).also {
                persistMusicFile(
                    event,
                    it,
                    deleteDelay,
                    filename,
                    introVolume,
                    inputStream,
                    member.effectiveName
                )
            }

        }
    }

    private fun setMusicFileForUser(
        event: SlashCommandInteractionEvent,
        requestingUserDto: UserDto,
        deleteDelay: Int?,
        filename: String,
        introVolume: Int,
        inputStream: InputStream
    ) {
        persistMusicFile(event, requestingUserDto, deleteDelay, filename, introVolume, inputStream, event.user.name)
    }

    private fun handleMusicUrl(
        event: SlashCommandInteractionEvent,
        mentionedMembers: List<Member>,
        deleteDelay: Int?,
        urlString: String,
        introVolume: Int
    ) {
        mentionedMembers.forEach { member ->
            UserDtoHelper.calculateUserDto(
                member.guild.idLong,
                member.idLong,
                member.isOwner,
                userService,
                introVolume
            ).also { persistMusicUrl(event, it, deleteDelay, urlString, urlString, member.effectiveName, introVolume) }
        }
    }

    private fun persistMusicFile(
        event: SlashCommandInteractionEvent,
        targetDto: UserDto,
        deleteDelay: Int?,
        filename: String,
        introVolume: Int,
        inputStream: InputStream?,
        memberName: String
    ) {
        val fileContents = runCatching { FileUtils.readInputStreamToByteArray(inputStream) }.getOrNull()
            ?: return event.hook.sendMessageFormat("Unable to read file '%s'", filename)
                .setEphemeral(true)
                .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))

        targetDto.musicDto?.let { existingDto ->
            updateMusicFileDto(filename, introVolume, fileContents, existingDto)
            sendUpdateMessage(event, memberName, filename, introVolume, deleteDelay)
        } ?: run {
            val newMusicDto = MusicDto(targetDto.discordId, targetDto.guildId, filename, introVolume, fileContents)
            musicFileService.createNewMusicFile(newMusicDto)
            targetDto.musicDto = newMusicDto
            userService.updateUser(targetDto)
            sendSuccessMessage(event, memberName, filename, introVolume, deleteDelay)
        }
    }

    private fun persistMusicUrl(
        event: SlashCommandInteractionEvent,
        targetDto: UserDto,
        deleteDelay: Int?,
        filename: String,
        url: String,
        memberName: String,
        introVolume: Int
    ) {
        val urlBytes = url.toByteArray()
        val musicFileDto = targetDto.musicDto
        if (musicFileDto == null) {
            val newMusicDto = MusicDto(targetDto.discordId, targetDto.guildId, filename, introVolume, urlBytes)
            musicFileService.createNewMusicFile(newMusicDto)
            targetDto.musicDto = newMusicDto
            userService.updateUser(targetDto)
            sendSuccessMessage(event, memberName, filename, introVolume, deleteDelay)
        } else {
            updateMusicFileDto(filename, introVolume, urlBytes, musicFileDto)
            sendUpdateMessage(event, memberName, filename, introVolume, deleteDelay)
        }
    }

    private fun updateMusicFileDto(
        filename: String,
        introVolume: Int,
        fileContents: ByteArray,
        musicFileDto: MusicDto
    ) {
        musicFileDto.apply {
            this.fileName = filename
            this.introVolume = introVolume
            this.musicBlob = fileContents
        }
        musicFileService.updateMusicFile(musicFileDto)
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
        memberName: String,
        filename: String,
        introVolume: Int,
        deleteDelay: Int?
    ) {
        event.hook
            .sendMessage("Successfully updated $memberName's intro song to '$filename' with volume '$introVolume'")
            .setEphemeral(true)
            .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
    }

    override val name: String
        get() = "introsong"

    override val description: String
        get() = "Upload an **MP3** file to play when you join a voice channel. Can use YouTube links instead."

    override val optionData: List<OptionData>
        get() {
            return listOf(
                OptionData(OptionType.STRING, USERS, "User whose intro to change"),
                OptionData(OptionType.STRING, LINK, "Link to set as your discord intro"),
                OptionData(OptionType.ATTACHMENT, ATTACHMENT, "Attachment (file) to set as your discord intro"),
                OptionData(OptionType.INTEGER, VOLUME, "Volume to set your intro to")
            )
        }

    companion object {
        private const val VOLUME = "volume"
        private const val USERS = "users"
        private const val LINK = "link"
        private const val ATTACHMENT = "attachment"
    }
}
