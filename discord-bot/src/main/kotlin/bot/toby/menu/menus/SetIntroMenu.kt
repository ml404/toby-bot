package bot.toby.menu.menus

import bot.toby.helpers.InputData
import bot.toby.helpers.IntroHelper
import bot.toby.helpers.MenuHelper.SET_INTRO
import bot.toby.helpers.UserDtoHelper
import core.menu.Menu
import core.menu.MenuContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class SetIntroMenu @Autowired constructor(
    private val introHelper: IntroHelper,
    private val userDtoHelper: UserDtoHelper
) : Menu {

    override fun handle(ctx: MenuContext, deleteDelay: Int) {
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)
        logger.info { "Intro menu event started" }
        val event = ctx.event
        event.deferReply(true).queue()

        val musicDtoId = event.selectedOptions.firstOrNull()?.value

        logger.info { "Replacing musicDto with id '$musicDtoId'" }

        val jdaUser = event.user
        val requestingUserDto = userDtoHelper.calculateUserDto(
            jdaUser.idLong,
            event.guild?.idLong!!,
            event.member?.isOwner == true
        )

        val musicDtoToReplace = requestingUserDto.musicDtos.firstOrNull { it.id == musicDtoId }
        if (musicDtoToReplace == null) {
            event.hook
                .sendMessage("Invalid selection or user data. Please try again.")
                .setEphemeral(true)
                .queue()
            return
        }
        val pendingIntro = introHelper.pendingIntro(requestingUserDto.guildId, requestingUserDto.discordId)
        if (pendingIntro != null) {
            runCatching {
                val pendingDtoAttachment = pendingIntro.attachment
                val input = if (pendingDtoAttachment != null) {
                    InputData.Attachment(pendingDtoAttachment)
                } else {
                    InputData.Url(pendingIntro.url!!)
                }
                introHelper.handleMedia(
                    event,
                    requestingUserDto,
                    deleteDelay,
                    input,
                    pendingIntro.volume,
                    musicDtoToReplace,
                    ctx.event.user.effectiveName,
                    pendingIntro.startMs,
                    pendingIntro.endMs,
                )
            }.onSuccess {
                logger.info { "Successfully set pending intro, removing from the cache for user '${requestingUserDto.discordId}'" }
                introHelper.clearPendingIntro(requestingUserDto.guildId, requestingUserDto.discordId)
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