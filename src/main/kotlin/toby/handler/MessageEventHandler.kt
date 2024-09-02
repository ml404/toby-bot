package toby.handler

import mu.KotlinLogging
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import toby.emote.Emotes
import toby.helpers.HttpHelper
import toby.jpa.service.*
import toby.managers.ButtonManager
import toby.managers.CommandManager
import toby.managers.MenuManager

@Service
class MessageEventHandler @Autowired constructor(
    private val jda: JDA,
    private val configService: IConfigService,
    brotherService: IBrotherService,
    private val userService: IUserService,
    musicFileService: IMusicFileService,
    excuseService: IExcuseService,
    httpHelper: HttpHelper,
    private val commandManager: CommandManager = CommandManager(
        configService,
        brotherService,
        userService,
        musicFileService,
        excuseService,
        httpHelper
    ),
    private val buttonManager: ButtonManager = ButtonManager(configService, userService, commandManager),
    private val menuManager: MenuManager = MenuManager(configService, httpHelper)
) : ListenerAdapter() {

    private val logger = KotlinLogging.logger {}

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

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (!event.user.isBot) {
            commandManager.handle(event)
        }
    }

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        event.deferReply(true).queue()
        if (!event.user.isBot) {
            buttonManager.handle(event)
        }
    }

    override fun onStringSelectInteraction(event: StringSelectInteractionEvent) {
        logger.info { "StringSelectInteractionEvent received on guild ${event.guild?.idLong}" }
        menuManager.handle(event)
    }
}
