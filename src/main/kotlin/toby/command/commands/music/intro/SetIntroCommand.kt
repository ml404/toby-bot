package toby.command.commands.music.intro

import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.components.selections.SelectOption
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.command.commands.music.IMusicCommand
import toby.helpers.IntroHelper
import toby.helpers.URLHelper
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.lavaplayer.PlayerManager
import toby.logging.DiscordLogger

class SetIntroCommand(
    private val introHelper: IntroHelper,
) : IMusicCommand {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

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
        logger.setGuildAndUserContext(event.guild, event.member)


        logger.info { "Inside handleMusicCommand" }
        if (!requestingUserDto.superUser && mentionedMembers.isNotEmpty()) {
            sendErrorMessage(event, deleteDelay!!)
            return
        }

        val attachmentOption = event.getOption(ATTACHMENT)
        val linkOption = event.getOption(LINK)?.asString.orEmpty()

        val mentionedUserDtoList = mentionedMembers.mapNotNull { introHelper.findUserById(it.idLong, it.guild.idLong) }

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
                    introHelper.findUserById(it.idLong, it.guild.idLong)!!,
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

        when {
            checkForOverIntroLimit(
                event.hook,
                requestingUserDto.discordId,
                requestingUserDto.musicDtos,
                linkOption,
                attachmentOption,
                introVolume
            ) -> {
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
            val builder = StringSelectMenu.create("intro").setPlaceholder(null)
            introList.forEach { builder.addOptions(SelectOption.of(it.fileName!!, it.id.toString())) }
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
