package toby.command.commands.music

import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping
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
import java.util.function.Consumer

class IntroSongCommand(private val userService: IUserService, private val musicFileService: IMusicFileService, private val configService: IConfigService) : IMusicCommand {
    private val USERS = "users"
    private val LINK = "link"
    private val ATTACHMENT = "attachment"
    override fun handle(ctx: CommandContext?, requestingUserDto: UserDto, deleteDelay: Int?) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(ctx: CommandContext?, instance: PlayerManager, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx!!.event
        event.deferReply().queue()
        val introVolume = calculateIntroVolume(event)
        val mentionedMembers = Optional.ofNullable(event.getOption(USERS)).map { obj: OptionMapping -> obj.mentions }.map { obj: Mentions -> obj.members }.orElse(emptyList())
        if (!requestingUserDto.superUser && mentionedMembers.isNotEmpty()) {
            sendErrorMessage(event, deleteDelay!!)
            return
        }
        val optionalAttachment = Optional.ofNullable(event.getOption(ATTACHMENT)).map { obj: OptionMapping -> obj.getAsAttachment() }
        val link = Optional.ofNullable(event.getOption(LINK)).map { obj: OptionMapping -> obj.asString }.orElse("")
        if (optionalAttachment.isPresent && URLHelper.isValidURL(link)) {
            event.hook.sendMessage(description).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        } else if (link.isNotEmpty()) {
            setIntroViaUrl(event, requestingUserDto, deleteDelay, URLHelper.fromUrlString(link), introVolume)
        } else {
            optionalAttachment.ifPresent { attachment: Message.Attachment -> setIntroViaDiscordAttachment(event, requestingUserDto, deleteDelay, attachment, introVolume) }
        }
    }

    private fun calculateIntroVolume(event: SlashCommandInteractionEvent): Int {
        val volumePropertyName = ConfigDto.Configurations.VOLUME.configValue
        val defaultVolume = configService.getConfigByName(volumePropertyName, event.guild?.id!!)?.value?.toIntOrNull()
        val volumeOptional = Optional.ofNullable(event.getOption(VOLUME)).map { obj: OptionMapping -> obj.asInt }
        var introVolume = volumeOptional.orElse(defaultVolume)
        if (introVolume < 1) introVolume = 1
        if (introVolume > 100) introVolume = 100
        return introVolume
    }

    private fun setIntroViaDiscordAttachment(event: SlashCommandInteractionEvent, requestingUserDto: UserDto?, deleteDelay: Int?, attachment: Message.Attachment, introVolume: Int) {
        if (attachment.getFileExtension() != "mp3") {
            event.hook.sendMessage("Please use mp3 files only").queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return
        } else if (attachment.size > 400000) {
            event.hook.sendMessage("Please keep the file size under 400kb").queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return
        }
        var inputStream: InputStream? = null
        try {
            inputStream = attachment.proxy.download().get()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        } catch (e: ExecutionException) {
            e.printStackTrace()
        }
        val mentionedMembers = Optional.ofNullable(event.getOption(USERS)).map { obj: OptionMapping -> obj.mentions }.map { obj: Mentions -> obj.members }.orElse(emptyList())
        if (mentionedMembers.size > 0 && requestingUserDto!!.superUser) {
            val finalInputStream = inputStream
            mentionedMembers.forEach(Consumer { member: Member ->
                val userDto = UserDtoHelper.calculateUserDto(member.guild.idLong, member.idLong, member.isOwner, userService, introVolume)
                persistMusicFile(event, userDto, deleteDelay, attachment.fileName, introVolume, finalInputStream, member.effectiveName)
            })
        } else persistMusicFile(event, requestingUserDto, deleteDelay, attachment.fileName, introVolume, inputStream, event.user.name)
    }

    private fun setIntroViaUrl(event: SlashCommandInteractionEvent, requestingUserDto: UserDto?, deleteDelay: Int?, optionalURI: Optional<URI>, introVolume: Int) {
        val mentionedMembers = Optional.ofNullable(event.getOption(USERS)).map { obj: OptionMapping -> obj.mentions }.map { obj: Mentions -> obj.members }.orElse(emptyList())
        val urlString = optionalURI.map { obj: URI -> obj.toString() }.orElse("")
        if (mentionedMembers.size > 0 && requestingUserDto!!.superUser) {
            mentionedMembers.forEach(Consumer { member: Member ->
                val userDto = UserDtoHelper.calculateUserDto(member.guild.idLong, member.idLong, member.isOwner, userService, introVolume)
                persistMusicUrl(event, userDto, deleteDelay, urlString, urlString, member.effectiveName, introVolume)
            })
        } else persistMusicUrl(event, requestingUserDto, deleteDelay, urlString, urlString, event.user.name, introVolume)
    }

    private fun persistMusicFile(event: SlashCommandInteractionEvent, targetDto: UserDto?, deleteDelay: Int?, filename: String, introVolume: Int, inputStream: InputStream?, memberName: String) {
        val fileContents: ByteArray? = runCatching {
            FileUtils.readInputStreamToByteArray(inputStream)
        }.onFailure { _ ->
            event.hook.sendMessageFormat("Unable to read file '%s'", filename)
                .setEphemeral(true)
                .queue {
                    invokeDeleteOnMessageResponse(deleteDelay!!)
                }
        }.getOrNull()

        val musicFileDto = targetDto!!.musicDto
        if (musicFileDto == null) {
            val musicDto = MusicDto(targetDto.discordId!!, targetDto.guildId, filename, introVolume, fileContents!!)
            musicFileService.createNewMusicFile(musicDto)
            targetDto.musicDto = musicDto
            userService.updateUser(targetDto)
            event.hook.sendMessageFormat("Successfully set %s's intro song to '%s' with volume '%d'", memberName, musicDto.fileName, musicDto.introVolume).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return
        }
        updateMusicFileDto(filename, introVolume, fileContents!!, musicFileDto)
        event.hook.sendMessageFormat("Successfully updated %s's intro song to '%s' with volume '%d'", memberName, filename, introVolume).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    private fun persistMusicUrl(event: SlashCommandInteractionEvent, targetDto: UserDto?, deleteDelay: Int?, filename: String, url: String, memberName: String, introVolume: Int) {
        val musicFileDto = targetDto!!.musicDto
        val urlBytes = url.toByteArray()
        if (musicFileDto == null) {
            val musicDto = MusicDto(targetDto.discordId!!, targetDto.guildId, filename, introVolume, urlBytes)
            musicFileService.createNewMusicFile(musicDto)
            targetDto.musicDto = musicDto
            userService.updateUser(targetDto)
            event.hook.sendMessageFormat("Successfully set %s's intro song to '%s' with volume '%d'", memberName, musicDto.fileName, musicDto.introVolume).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return
        }
        updateMusicFileDto(filename, introVolume, urlBytes, musicFileDto)
        event.hook.sendMessageFormat("Successfully updated %s's intro song to '%s' with volume '%d'", memberName, filename, introVolume).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    private fun updateMusicFileDto(filename: String, introVolume: Int, fileContents: ByteArray, musicFileDto: MusicDto) {
        musicFileDto.fileName = filename
        musicFileDto.introVolume = introVolume
        musicFileDto.musicBlob = fileContents
        musicFileService.updateMusicFile(musicFileDto)
    }

    override val name: String
        get() = "introsong"
    override val description: String
        get() = "Upload a **MP3** file to play when you join a voice channel. Can use youtube links instead."
    override val optionData: List<OptionData>
        get() {
            val users = OptionData(OptionType.STRING, USERS, "User whose intro to change")
            val link = OptionData(OptionType.STRING, LINK, "Link to set as your discord intro")
            val attachment = OptionData(OptionType.ATTACHMENT, ATTACHMENT, "Attachment (file) to set as your discord intro")
            val volume = OptionData(OptionType.INTEGER, VOLUME, "volume to set your intro to")
            return listOf(users, link, attachment, volume)
        }

    companion object {
        private const val VOLUME = "volume"
    }
}
