package toby.menu.menus

import mu.KotlinLogging
import toby.helpers.IntroHelper
import toby.helpers.UserDtoHelper
import toby.menu.IMenu
import toby.menu.MenuContext


class IntroMenu(
    private val introHelper: IntroHelper,
    private val userDtoHelper: UserDtoHelper
) : IMenu {

    private val logger = KotlinLogging.logger {}

    override fun handle(ctx: MenuContext, deleteDelay: Int) {
        logger.info { "Intro menu event started for guild ${ctx.guild.idLong}" }
        val event = ctx.selectEvent
        event.deferReply().queue()

        val selectedIndex = event.selectedOptions.firstOrNull()?.value?.toIntOrNull()

        if (selectedIndex == null) {
            event.hook
                .sendMessage("Invalid selection or user data. Please try again.")
                .setEphemeral(true).queue()
            return
        }

        val jdaUser = event.user
        val requestingUserDto = userDtoHelper.calculateUserDto(
            event.guild?.idLong ?: 0,
            jdaUser.idLong,
            event.member?.isOwner ?: false
        )

        val musicDtoToReplace = requestingUserDto.musicDtos[selectedIndex]
        val pendingIntroTriple = introHelper.pendingIntros[requestingUserDto.discordId]
        if (pendingIntroTriple != null) {
            val (pendingDtoAttachment, url, introVolume) = pendingIntroTriple
            runCatching {
                introHelper.handleMedia(
                    ctx.event,
                    requestingUserDto,
                    deleteDelay,
                    pendingDtoAttachment,
                    url,
                    introVolume,
                    musicDtoToReplace,
                    ctx.selectEvent.user.effectiveName
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