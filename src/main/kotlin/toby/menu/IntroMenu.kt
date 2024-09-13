package toby.menu

import mu.KotlinLogging
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import toby.helpers.IntroHelper
import toby.helpers.UserDtoHelper


class IntroMenu(
    private val introHelper: IntroHelper,
    private val userDtoHelper: UserDtoHelper
) : IMenu {

    private val logger = KotlinLogging.logger {}

    override fun handle(ctx: MenuContext, deleteDelay: Int) {
        logger.info { "Intro menu event started for guild ${ctx.guild.idLong}" }
        val event = ctx.selectEvent as? StringSelectInteractionEvent
        event?.deferReply()?.queue()

        val selectedIndex = event?.selectedOptions?.firstOrNull()?.value?.toIntOrNull()

        if (selectedIndex == null) {
            event?.hook
                ?.sendMessage("Invalid selection or user data. Please try again.")
                ?.setEphemeral(true)?.queue()
            return
        }

        val jdaUser = event.user
        val requestingUserDto = userDtoHelper.calculateUserDto(
            event.guild?.idLong ?: 0,
            jdaUser.idLong,
            event.member?.isOwner ?: false
        )

        val musicDtoToReplace = requestingUserDto.musicDtos[selectedIndex]
        val attachmentIntPair = introHelper.pendingIntros[requestingUserDto.discordId]
        if (attachmentIntPair != null) {
            val (pendingDtoAttachment, introVolume) = attachmentIntPair
            runCatching {
                introHelper.handleAttachment(
                    ctx.event,
                    requestingUserDto,
                    deleteDelay,
                    pendingDtoAttachment,
                    introVolume,
                    musicDtoToReplace
                )
            }.onFailure {
                logger.error(it) { "Error handling intro replacement" }
                event.hook
                    .sendMessage("Something went wrong while processing your selection. Please try again.")
                    .setEphemeral(true).queue()
            }
        }
    }

    override val name: String
        get() = "intro"
}