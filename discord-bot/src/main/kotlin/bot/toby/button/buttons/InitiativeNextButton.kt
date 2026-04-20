package bot.toby.button.buttons

import bot.toby.helpers.DnDHelper
import common.events.CampaignEventType
import core.button.Button
import core.button.ButtonContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import web.service.SessionLogPublisher

@Component
class InitiativeNextButton @Autowired constructor(
    private val dndHelper: DnDHelper,
    private val sessionLog: SessionLogPublisher
) : Button {
    override val name: String
        get() = "init:next"
    override val description: String
        get() = "Move the initiative table onto the next member"

    override fun handle(ctx: ButtonContext, requestingUserDto: database.dto.UserDto, deleteDelay: Int) {
        val event = ctx.event
        val hook = event.hook
        val guildId = ctx.guild.idLong
        dndHelper.incrementTurnTable(guildId, hook, event, deleteDelay)

        val state = dndHelper.stateFor(guildId)
        val current = state.sortedEntries.getOrNull(state.initiativeIndex.get())
        sessionLog.publish(
            guildId = guildId,
            type = CampaignEventType.INITIATIVE_NEXT,
            actorDiscordId = event.user.idLong,
            actorName = event.member?.effectiveName ?: event.user.effectiveName,
            payload = mapOf(
                "currentName" to current?.name,
                "currentIndex" to state.initiativeIndex.get()
            )
        )
    }
}
