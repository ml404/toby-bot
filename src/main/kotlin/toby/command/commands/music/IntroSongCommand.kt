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
import java.util.concurrent.ExecutionException

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
        val mentionedMembers = getMentionedMembers(event)

        if (!requestingUserDto.superUser && mentionedMembers.isNotEmpty()) {
            sendErrorMessage(event, deleteDelay!!)
            return
        }

        val attachmentOption = event.getOption(ATTACHMENT)
        val linkOption = event.getOption(LINK)?.asString.orEmpty()

        when {
            attachmentOption != null && URLHelper.isValidURL(linkOption) -> {
                event.hook.sendMessage(description).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            }
            linkOption.isNotEmpty() -> {
                setIntroViaUrl(event, requestingUserDto, deleteDelay, URLHelper.fromUrlString(linkOption), introVolume)
            }
            attachmentOption != null -> {
                setIntroViaDiscordAttachment(event, requestingUserDto, deleteDelay, attachmentOption.asAttachment, introVolume)
            }
        }
    }

    private fun calculateIntroVolume(event: SlashCommandInteractionEvent): Int {
        val volumePropertyName = ConfigDto.Configurations.VOLUME.configValue
        val defaultVolume = configService.getConfigByName(volumePropertyName, event.guild?.id)?.value?.toIntOrNull()
        val volumeOption = event.getOption(VOLUME)?.asInt
        var introVolume = volumeOption ?: defaultVolume ?: 100
        introVolume = introVolume.coerceIn(1, 100)
        return introVolume
    }

    private fun getMentionedMembers(event: SlashCommandInteractionEvent): List<Member> {
        return event.getOption(USERS)?.mentions?.members.orEmpty()
    }

    private fun setIntroViaDiscordAttachment(
        event: SlashCommandInteractionEvent, requestingUserDto: UserDto?, deleteDelay: Int?, attachment: Message.Attachment, introVolume: Int
    ) {
        when {
            attachment.fileExtension != "mp3" -> {
                event.hook.sendMessage("Please use mp3 files only").queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            }
            attachment.size > 400000 -> {
                event.hook.sendMessage("Please keep the file size under 400kb").queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            }
            else -> {
                val inputStream = downloadAttachment(attachment)
                val mentionedMembers = getMentionedMembers(event)
                if (mentionedMembers.isNotEmpty() && requestingUserDto!!.superUser) {
                    mentionedMembers.forEach { member ->
                        val userDto = UserDtoHelper.calculateUserDto(member.guild.idLong, member.idLong, member.isOwner, userService, introVolume)
                        userDto?.let {
                            persistMusicFile(event,
                                it, deleteDelay, attachment.fileName, introVolume, inputStream, member.effectiveName)
                        }
                    }
                } else {
                    requestingUserDto?.let {
                        persistMusicFile(event,
                            it, deleteDelay, attachment.fileName, introVolume, inputStream, event.user.name)
                    }
                }
            }
        }
    }

    private fun downloadAttachment(attachment: Message.Attachment): InputStream? {
        return try {
            attachment.proxy.download().get()
        } catch (e: InterruptedException) {
            e.printStackTrace()
            null
        } catch (e: ExecutionException) {
            e.printStackTrace()
            null
        }
    }

    private fun setIntroViaUrl(
        event: SlashCommandInteractionEvent, requestingUserDto: UserDto?, deleteDelay: Int?, optionalURI: Optional<URI>, introVolume: Int
    ) {
        val mentionedMembers = getMentionedMembers(event)
        val urlString = optionalURI.map(URI::toString).orElse("")
        if (mentionedMembers.isNotEmpty() && requestingUserDto!!.superUser) {
            mentionedMembers.forEach { member ->
                val userDto = UserDtoHelper.calculateUserDto(member.guild.idLong, member.idLong, member.isOwner, userService, introVolume)
                userDto?.let {
                    persistMusicUrl(event,
                        it, deleteDelay, urlString, urlString, member.effectiveName, introVolume)
                }
            }
        } else {
            requestingUserDto?.let {
                persistMusicUrl(event,
                    it, deleteDelay, urlString, urlString, event.user.name, introVolume)
            }
        }
    }

    private fun persistMusicFile(
        event: SlashCommandInteractionEvent, targetDto: UserDto, deleteDelay: Int?, filename: String, introVolume: Int, inputStream: InputStream?, memberName: String
    ) {
        val fileContents = runCatching {
            FileUtils.readInputStreamToByteArray(inputStream)
        }.onFailure {
            event.hook.sendMessageFormat("Unable to read file '%s'", filename).setEphemeral(true).queue {
                invokeDeleteOnMessageResponse(deleteDelay!!)
            }
        }.getOrNull()

        if (fileContents == null) return

        val musicFileDto = targetDto.musicDto
        if (musicFileDto == null) {
            val newMusicDto = MusicDto(targetDto.discordId!!, targetDto.guildId, filename, introVolume, fileContents)
            musicFileService.createNewMusicFile(newMusicDto)
            targetDto.musicDto = newMusicDto
            userService.updateUser(targetDto)
            event.hook.sendMessageFormat("Successfully set %s's intro song to '%s' with volume '%d'", memberName, filename, introVolume)
                .setEphemeral(true)
                .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        } else {
            updateMusicFileDto(filename, introVolume, fileContents, musicFileDto)
            event.hook.sendMessageFormat("Successfully updated %s's intro song to '%s' with volume '%d'", memberName, filename, introVolume)
                .setEphemeral(true)
                .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
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
        val musicFileDto = targetDto.musicDto
        val urlBytes = url.toByteArray()
        if (musicFileDto == null) {
            val newMusicDto = MusicDto(targetDto.discordId!!, targetDto.guildId, filename, introVolume, urlBytes)
            musicFileService.createNewMusicFile(newMusicDto)
            targetDto.musicDto = newMusicDto
            userService.updateUser(targetDto)
            event.hook.sendMessageFormat("Successfully set %s's intro song to '%s' with volume '%d'", memberName, filename, introVolume)
                .setEphemeral(true)
                .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        } else {
            updateMusicFileDto(filename, introVolume, urlBytes, musicFileDto)
            event.hook.sendMessageFormat("Successfully updated %s's intro song to '%s' with volume '%d'", memberName, filename, introVolume)
                .setEphemeral(true)
                .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        }
    }

    private fun updateMusicFileDto(filename: String, introVolume: Int, fileContents: ByteArray, musicFileDto: MusicDto) {
        musicFileDto.apply {
            this.fileName = filename
            this.introVolume = introVolume
            this.musicBlob = fileContents
        }
        musicFileService.updateMusicFile(musicFileDto)
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
