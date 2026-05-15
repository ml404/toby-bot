package bot.toby.command

import core.command.Command.Companion.replyAndDelete
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import org.springframework.stereotype.Component

/**
 * Shared permission-check helpers for moderation-style commands (Kick,
 * Move, etc.) that previously inlined the same `canInteract` / role-
 * permission / bot-permission ladder per command. Each check is a
 * predicate that, on failure, sends a themed reply and returns `false`;
 * on success it returns `true` so the caller can move on.
 *
 * Kept as an injectable component so tests can swap in a deterministic
 * stub (e.g. always-true) without spinning up real JDA permission state.
 */
interface PermissionValidator {

    /** True iff [actor] can interact with [target] AND holds [permission]. */
    fun actorMayActOn(
        event: SlashCommandInteractionEvent,
        actor: Member,
        target: Member,
        permission: Permission,
        action: String,
        deleteDelay: Int,
    ): Boolean

    /** True iff the bot (`guild.selfMember`) holds [permission]. */
    fun botMayAct(
        event: SlashCommandInteractionEvent,
        bot: Member,
        target: Member,
        permission: Permission,
        action: String,
        deleteDelay: Int,
        requireCanInteract: Boolean = false,
    ): Boolean
}

@Component
class DefaultPermissionValidator : PermissionValidator {

    override fun actorMayActOn(
        event: SlashCommandInteractionEvent,
        actor: Member,
        target: Member,
        permission: Permission,
        action: String,
        deleteDelay: Int,
    ): Boolean {
        if (actor.canInteract(target) && actor.hasPermission(permission)) return true
        event.hook.replyAndDelete("You can't $action ${target.effectiveName}", deleteDelay)
        return false
    }

    override fun botMayAct(
        event: SlashCommandInteractionEvent,
        bot: Member,
        target: Member,
        permission: Permission,
        action: String,
        deleteDelay: Int,
        requireCanInteract: Boolean,
    ): Boolean {
        val hasPermission = bot.hasPermission(permission)
        val canInteract = !requireCanInteract || bot.canInteract(target)
        if (hasPermission && canInteract) return true
        event.hook.replyAndDelete("I'm not allowed to $action ${target.effectiveName}", deleteDelay)
        return false
    }
}
