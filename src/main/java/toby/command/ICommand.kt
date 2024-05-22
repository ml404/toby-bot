package toby.command

import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import toby.jpa.dto.UserDto
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

interface ICommand {
    fun handle(ctx: CommandContext?, requestingUserDto: UserDto, deleteDelay: Int?)
    val name: String?
    val description: String?
    fun getErrorMessage(serverOwner: String?): String? {
        return String.format("You do not have adequate permissions to use this command, if you believe this is a mistake talk to the server owner: %s", serverOwner)
    }

    fun sendErrorMessage(event: SlashCommandInteractionEvent, deleteDelay: Int) {
        val owner = event.guild!!.owner
        event.hook.sendMessageFormat(getErrorMessage(owner!!.effectiveName)!!).queue { message: Message -> deleteAfter(message, deleteDelay) }
    }

    val slashCommand: SlashCommandData?
        get() = Commands.slash(name!!, description!!)
    val optionData: List<OptionData?>?
        get() = emptyList<OptionData>()

    companion object {
        @JvmStatic
        fun deleteAfter(interactionHook: InteractionHook, delay: Int) {
            interactionHook.deleteOriginal().queueAfter(delay.toLong(), TimeUnit.SECONDS)
        }

        fun deleteAfter(message: Message, delay: Int) {
            message.delete().queueAfter(delay.toLong(), TimeUnit.SECONDS)
        }

        @JvmStatic
        fun invokeDeleteOnMessageResponse(deleteDelay: Int): Consumer<Message> {
            return Consumer { message: Message -> deleteAfter(message, deleteDelay) }
        }

        @JvmStatic
        fun invokeDeleteOnHookResponse(deleteDelay: Int): Consumer<InteractionHook> {
            return Consumer { hook: InteractionHook -> deleteAfter(hook, deleteDelay) }
        }
    }
}