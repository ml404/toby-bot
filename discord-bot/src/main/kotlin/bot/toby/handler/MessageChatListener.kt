package bot.toby.handler

import bot.toby.emote.Emotes
import common.logging.DiscordLogger
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.stereotype.Service

/**
 * Chat-keyword reactions on plain (non-slash) messages: the "that's not
 * my name" / "what's up champ?" / "YEAH????" replies and the
 * mention-back canned response. Split out of the old
 * `MessageEventHandler` god-class so each interaction surface has its
 * own focused listener.
 */
@Service
class MessageChatListener : ListenerAdapter() {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun onMessageReceived(event: MessageReceivedEvent) {
        val message = event.message
        val author = event.author
        val channel = event.channel

        runCatching {
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
                    channel.sendMessageFormat(
                        "Hey %s, what's up champ?",
                        member?.effectiveName ?: author.name,
                        jessEmote
                    ).queue()
                }

                messageStringLowercase.contains("yeah") -> {
                    channel.sendMessage("YEAH????").queue()
                }

                event.jda.selfUser.let { message.mentions.isMentioned(it) } -> {
                    channel.sendMessage("Don't talk to me").queue()
                }
            }
        }.onFailure {
            // Handle DM context, log the case
            logger.setGuildContext(null)
            logger.warn("A message was sent by '${author.name}' in a DM context.")
        }
    }
}
