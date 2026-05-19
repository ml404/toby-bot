package core.command

import core.log.Loggable
import core.managers.Named
import database.dto.UserDto
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

interface Command : Loggable, Named {
    fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int)
    override val name: String
    val description: String

    fun getErrorMessage(serverOwner: String?): String {
        return "You do not have adequate permissions to use this command, if you believe this is a mistake talk to $serverOwner"
    }

    fun sendErrorMessage(event: SlashCommandInteractionEvent, deleteDelay: Int) {
        val owner = event.guild?.owner
        val ownerName = owner?.effectiveName ?: "the server owner"
        event.hook.sendMessageFormat(getErrorMessage(ownerName)).queue { it.deleteAfter(deleteDelay) }
    }

    val slashCommand: SlashCommandData get() = Commands.slash(name, description)

    val optionData: List<OptionData> get() = emptyList()

    val subCommands: List<SubcommandData> get() = emptyList()

    companion object {
        @JvmStatic
        fun InteractionHook.deleteAfter(delay: Int) {
            this.deleteOriginal().queueAfter(delay.toLong(), TimeUnit.SECONDS)
        }

        @JvmStatic
        fun Message.deleteAfter(delay: Int) {
            this.delete().queueAfter(delay.toLong(), TimeUnit.SECONDS)
        }

        @JvmStatic
        fun invokeDeleteOnMessageResponse(deleteDelay: Int): Consumer<Message> {
            return Consumer { message -> message.deleteAfter(deleteDelay) }
        }

        @JvmStatic
        fun invokeDeleteOnHookResponse(deleteDelay: Int): Consumer<InteractionHook> {
            return Consumer { hook -> hook.deleteAfter(deleteDelay) }
        }

        /**
         * Reply-and-delete wrappers for the
         * `event.hook.sendMessage*(...).queue(invokeDeleteOnMessageResponse(d))`
         * pattern. Four shapes covered: plain text / ephemeral text /
         * embed / ephemeral embed. Callers using `sendMessageFormat`
         * pre-format with a Kotlin string template at the call site —
         * the helpers take `String` only.
         *
         * All four shapes route through [sendAndDelete] so the queue +
         * deletion side lives in one place; only the JDA action factory
         * differs per shape.
         */
        private inline fun InteractionHook.sendAndDelete(
            ephemeral: Boolean,
            deleteDelay: Int,
            action: InteractionHook.() -> net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction<net.dv8tion.jda.api.entities.Message>
        ) {
            action().setEphemeral(ephemeral).queue(invokeDeleteOnMessageResponse(deleteDelay))
        }

        fun InteractionHook.replyAndDelete(message: String, deleteDelay: Int) =
            sendAndDelete(ephemeral = false, deleteDelay) { sendMessage(message) }

        fun InteractionHook.replyEphemeralAndDelete(message: String, deleteDelay: Int) =
            sendAndDelete(ephemeral = true, deleteDelay) { sendMessage(message) }

        fun InteractionHook.replyEmbedAndDelete(embed: MessageEmbed, deleteDelay: Int) =
            sendAndDelete(ephemeral = false, deleteDelay) { sendMessageEmbeds(embed) }

        fun InteractionHook.replyEphemeralEmbedAndDelete(embed: MessageEmbed, deleteDelay: Int) =
            sendAndDelete(ephemeral = true, deleteDelay) { sendMessageEmbeds(embed) }
    }
}
