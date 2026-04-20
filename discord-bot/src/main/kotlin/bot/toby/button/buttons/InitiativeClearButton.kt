package bot.toby.button.buttons

import bot.toby.helpers.DnDHelper
import common.events.CampaignEventType
import core.button.Button
import core.button.ButtonContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import web.service.SessionLogPublisher

@Component
class InitiativeClearButton @Autowired constructor(
    private val dnDHelper: DnDHelper,
    private val sessionLog: SessionLogPublisher
) : Button {
    override val name: String
        get() = "init:clear"
    override val description: String
        get() = "Clear and delete the initiative table"

    override fun handle(ctx: ButtonContext, requestingUserDto: database.dto.UserDto, deleteDelay: Int) {
        val event = ctx.event
        val hook = ctx.event.hook
        val guildId = ctx.guild.idLong
        dnDHelper.clearInitiative(guildId, hook, event)

        sessionLog.publish(
            guildId = guildId,
            type = CampaignEventType.INITIATIVE_CLEARED,
            actorDiscordId = event.user.idLong,
            actorName = event.member?.effectiveName ?: event.user.effectiveName
        )
    }
}
