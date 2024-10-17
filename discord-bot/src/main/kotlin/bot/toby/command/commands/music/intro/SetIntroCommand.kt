package bot.toby.command.commands.music.intro

import database.dto.MusicDto
import database.dto.UserDto
import bot.toby.command.CommandContext
import bot.toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import bot.toby.command.commands.music.IMusicCommand
import bot.toby.helpers.IntroHelper
import bot.toby.helpers.MenuHelper.SET_INTRO
import bot.toby.helpers.URLHelper
import bot.toby.lavaplayer.PlayerManager
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.components.selections.SelectOption
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class SetIntroCommand @Autowired constructor(
    private val introHelper: IntroHelper
) : IMusicCommand {

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(
        ctx: CommandContext,
        instance: PlayerManager,
        requestingUserDto: UserDto,
        deleteDelay: Int?
    ) {
        val event = ctx.event
        event.deferReply(true).queue()
        val introVolume = introHelper.calculateIntroVolume(event)
        val mentionedMembers = event.getOptionMentionedMembers()
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)


        logger.info { "Inside handleMusicCommand" }
        if (!requestingUserDto.superUser && mentionedMembers.isNotEmpty()) {
            sendErrorMessage(event, deleteDelay!!)
            return
        }

        val attachmentOption = event.getOption(ATTACHMENT)
        val linkOption = event.getOption(LINK)?.asString.orEmpty()

        val mentionedUserDtoList = mentionedMembers.map { introHelper.findUserById(it.idLong, it.guild.idLong) }

        if (mentionedUserDtoList.isEmpty()) {
            checkAndSetIntro(
                event,
                requestingUserDto,
                linkOption,
                event.user.effectiveName,
                deleteDelay,
                introVolume,
                attachmentOption
            )
        } else {
            mentionedMembers.forEach {
                checkAndSetIntro(
                    event,
                    introHelper.findUserById(it.idLong, it.guild.idLong),
                    linkOption,
                    it.effectiveName,
                    deleteDelay,
                    introVolume,
                    attachmentOption
                )
            }
        }

    }

    private fun checkAndSetIntro(
        event: SlashCommandInteractionEvent,
        requestingUserDto: UserDto,
        linkOption: String,
        userName: String,
        deleteDelay: Int?,
        introVolume: Int,
        attachmentOption: OptionMapping?
    ) {
        introHelper.validateIntroLength(linkOption) { isOverLimit ->
            // Handle overly long intro case
            if (isOverLimit) {
                logger.info { "Intro was rejected for being over the specified intro limit length of 20 seconds" }
                event.hook
                    .sendMessage("Intro provided was over 20 seconds long, out of courtesy please pick a shorter intro.")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                return@validateIntroLength
            } else {
                validateAndSetIntro(
                    event,
                    requestingUserDto,
                    linkOption,
                    attachmentOption,
                    introVolume,
                    userName,
                    deleteDelay
                )
            }
        }
    }

    private fun validateAndSetIntro(
        event: SlashCommandInteractionEvent,
        requestingUserDto: UserDto,
        linkOption: String,
        attachmentOption: OptionMapping?,
        introVolume: Int,
        userName: String,
        deleteDelay: Int?
    ) {
        when {
            checkForOverIntroLimit(
                event.hook,
                requestingUserDto.discordId,
                requestingUserDto.musicDtos,
                linkOption,
                attachmentOption,
                introVolume
            )
                -> {
                return
            }

            linkOption.isNotEmpty() -> {
                val optionalURI = URLHelper.fromUrlString(linkOption)
                introHelper.handleUrl(
                    event,
                    requestingUserDto,
                    userName = userName,
                    deleteDelay,
                    optionalURI,
                    introVolume
                )
            }

            attachmentOption != null -> {
                introHelper.handleAttachment(
                    event,
                    requestingUserDto,
                    userName,
                    deleteDelay,
                    attachmentOption.asAttachment,
                    introVolume
                )
            }

            else -> {
                event.hook.sendMessage("Please provide a valid link or attachment")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            }
        }
    }

    private fun checkForOverIntroLimit(
        hook: InteractionHook,
        discordId: Long,
        introList: MutableList<MusicDto>,
        linkOption: String? = null,
        attachmentOption: OptionMapping? = null,
        introVolume: Int
    ): Boolean {
        if (introList.size >= LIMIT) {
            introHelper.pendingIntros[discordId] = Triple(attachmentOption?.asAttachment, linkOption, introVolume)
            val builder = StringSelectMenu.create(SET_INTRO).setPlaceholder(null)
            introList
                .sortedBy { it.index }
                .forEach { builder.addOptions(SelectOption.of(it.fileName!!, it.id.toString())) }
            val stringSelectMenu = builder.build()
            hook.sendMessage("Select the intro you'd like to replace with your new upload as we only allow $LIMIT intros")
                .setActionRow(stringSelectMenu)
                .setEphemeral(true)
                .queue()
            return true
        }
        return false
    }

    private fun SlashCommandInteractionEvent.getOptionMentionedMembers(): List<Member> {
        return this.getOption(USERS)?.mentions?.members.orEmpty()
    }


    override val name: String
        get() = "setintro"

    override val description: String
        get() = "Upload an **MP3** file to play when you join a voice channel. Can use YouTube links instead."

    override val optionData: List<OptionData>
        get() {
            return listOf(
                OptionData(OptionType.MENTIONABLE, USERS, "User whose intro to change"),
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
        private const val LIMIT = 3
    }
}
