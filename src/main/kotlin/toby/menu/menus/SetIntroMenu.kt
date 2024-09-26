package toby.menu.menus

import toby.helpers.IntroHelper
import toby.helpers.MenuHelper.SET_INTRO
import toby.helpers.UserDtoHelper
import toby.menu.IMenu
import toby.menu.MenuContext


class SetIntroMenu(
    private val introHelper: IntroHelper,
    private val userDtoHelper: UserDtoHelper
) : IMenu {

    override fun handle(ctx: MenuContext, deleteDelay: Int) {
        logger.setGuildAndUserContext(ctx.guild, ctx.member)
        logger.info { "Intro menu event started" }
        val event = ctx.event
        event.deferReply(true).queue()

        val musicDtoId = event.selectedOptions.firstOrNull()?.value

        logger.info { "Replacing musicDto with id '$musicDtoId'" }

        val jdaUser = event.user
        val requestingUserDto = userDtoHelper.calculateUserDto(
            event.guild?.idLong!!,
            jdaUser.idLong,
            event.member?.isOwner ?: false
        )

        val musicDtoToReplace = requestingUserDto.musicDtos.firstOrNull { it.id == musicDtoId }
        if (musicDtoToReplace == null) {
            event.hook
                .sendMessage("Invalid selection or user data. Please try again.")
                .setEphemeral(true)
                .queue()
            return
        }
        val pendingIntroTriple = introHelper.pendingIntros[requestingUserDto.discordId]
        if (pendingIntroTriple != null) {
            val (pendingDtoAttachment, url, introVolume) = pendingIntroTriple
            runCatching {
                introHelper.handleMedia(
                    event,
                    requestingUserDto,
                    deleteDelay,
                    pendingDtoAttachment,
                    url,
                    introVolume,
                    musicDtoToReplace,
                    ctx.event.user.effectiveName
                )
            }.onSuccess {
                logger.info { "Successfully set pending intro, removing from the cache for user '${requestingUserDto.discordId}'" }
                introHelper.pendingIntros.remove(requestingUserDto.discordId)
            }.onFailure {
                logger.error { "Error handling intro replacement" }
                event.hook
                    .sendMessage("Something went wrong while processing your selection. Please try again.")
                    .setEphemeral(true)
                    .queue()
            }
        }
    }

    override val name: String get() = SET_INTRO
}