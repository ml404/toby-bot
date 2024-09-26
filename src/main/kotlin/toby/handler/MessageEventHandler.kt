package toby.handler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.Command
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import toby.emote.Emotes
import toby.logging.DiscordLogger
import toby.managers.ButtonManager
import toby.managers.CommandManager
import toby.managers.MenuManager
import kotlin.coroutines.CoroutineContext

@Service
class MessageEventHandler @Autowired constructor(
    private val jda: JDA,
    private val commandManager: CommandManager,
    private val buttonManager: ButtonManager,
    private val menuManager: MenuManager
) : ListenerAdapter(), CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.Default
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun onMessageReceived(event: MessageReceivedEvent) {
        val message = event.message
        val author = event.author
        val channel = event.channel
        val guild = event.guild
        val member = event.member

        if (author.isBot || event.isWebhookMessage) return

        val messageStringLowercase = message.contentRaw.lowercase()

        when {
            messageStringLowercase.contains("toby") || messageStringLowercase.contains("tobs") -> {
                val tobyEmote = guild.jda.getEmojiById(Emotes.TOBY)
                channel.sendMessageFormat(
                    "%s... that's not my name %s",
                    member?.effectiveName ?: author.name,
                    tobyEmote
                ).queue()
                message.addReaction(tobyEmote!!).queue()
            }

            messageStringLowercase.trim() == "sigh" -> {
                val jessEmote = guild.jda.getEmojiById(Emotes.JESS)
                channel.sendMessageFormat("Hey %s, what's up champ?", member?.effectiveName ?: author.name, jessEmote)
                    .queue()
            }

            messageStringLowercase.contains("yeah") -> {
                channel.sendMessage("YEAH????").queue()
            }

            jda.selfUser.let { message.mentions.isMentioned(it) } -> {
                channel.sendMessage("Don't talk to me").queue()
            }
        }
    }

    override fun onCommandAutoCompleteInteraction(event: CommandAutoCompleteInteractionEvent) {
        if (event.name == "help" && event.focusedOption.name == "command") {
            val input = event.focusedOption.value // Get the current user input
            val suggestions = suggestCommands(input) // Get matching commands
            val optionChoices = suggestions.map { Command.Choice(it, it) } // Convert suggestions to choices

            // Reply with the suggestions
            event.replyChoices(optionChoices).queue()
        }
    }

    private fun suggestCommands(input: String): List<String> {
        return commandManager.allCommands.filter { it.name.startsWith(input, ignoreCase = true) }.map { it.name }
    }


    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        logger.setGuildAndUserContext(event.guild, event.member)
        logger.info { "SlashCommandInteractionEvent '${event.name}' received" }
        if (!event.user.isBot) {
            launch {
                logger.info { "Launching coroutine for '${event.name}'" }
                commandManager.handle(event)
            }.invokeOnCompletion {
                logger.info { "Finished coroutine for '${event.name}'" }
            }
        }
    }

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        event.deferReply(true).queue()
        if (!event.user.isBot) {
            launch {
                buttonManager.handle(event)
            }
        }
    }

    override fun onStringSelectInteraction(event: StringSelectInteractionEvent) {
        logger.setGuildAndUserContext(event.guild, event.member)
        logger.info { "StringSelectInteractionEvent '${event.componentId}' received" }
        launch {
            logger.info { "Launching coroutine for '${event.componentId}'" }
            menuManager.handle(event)
        }.invokeOnCompletion {
            logger.info { "Finished coroutine for '${event.componentId}'" }
        }
    }
}
