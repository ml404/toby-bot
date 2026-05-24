package bot.toby.handler

import bot.toby.emote.Emotes
import com.github.benmanes.caffeine.cache.Caffeine
import common.logging.DiscordLogger
import database.service.activity.MessageActivityBuffer
import database.service.leveling.XpAwardService
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/**
 * Chat-keyword reactions on plain (non-slash) messages: the "that's not
 * my name" / "what's up champ?" / "YEAH????" replies and the
 * mention-back canned response. Split out of the old
 * `MessageEventHandler` god-class so each interaction surface has its
 * own focused listener.
 *
 * Also the hook where messages grant XP — every non-bot message awards a
 * random `MESSAGE_XP_MIN..MESSAGE_XP_MAX` chunk, subject to a per-user
 * 60s cooldown (Tatsu/MEE6-style anti-spam) and the daily XP cap.
 */
@Service
class MessageChatListener @Autowired constructor(
    private val xpAwardService: XpAwardService,
    private val messageActivityBuffer: MessageActivityBuffer,
) : ListenerAdapter() {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    // (guildId, discordId) -> last-award timestamp. Caffeine evicts entries
    // older than the cooldown so the map can't grow unbounded; size is also
    // capped as a hard ceiling.
    private val lastAwardAt = Caffeine.newBuilder()
        .expireAfterWrite(MESSAGE_XP_COOLDOWN_SECONDS, TimeUnit.SECONDS)
        .maximumSize(10_000)
        .build<Pair<Long, Long>, Long>()

    override fun onMessageReceived(event: MessageReceivedEvent) {
        val message = event.message
        val author = event.author
        val channel = event.channel

        runCatching {
            val guild = event.guild
            val member = event.member
            if (author.isBot || event.isWebhookMessage) return

            // Coarse per-guild per-day counter feeding the moderation Activity
            // chart. Runs before awardMessageXp so even a user past their daily
            // XP cap still shows up in the messages-per-day total.
            messageActivityBuffer.record(guild.idLong)

            awardMessageXp(event)

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

    private fun awardMessageXp(event: MessageReceivedEvent) {
        if (!event.isFromGuild) return
        val guildId = event.guild.idLong
        val discordId = event.author.idLong
        val key = guildId to discordId
        val now = System.currentTimeMillis()
        val previous = lastAwardAt.getIfPresent(key)
        if (previous != null && now - previous < MESSAGE_XP_COOLDOWN_SECONDS * 1000L) return
        lastAwardAt.put(key, now)
        val amount = ThreadLocalRandom.current().nextLong(MESSAGE_XP_MIN, MESSAGE_XP_MAX + 1)
        xpAwardService.award(
            discordId = discordId,
            guildId = guildId,
            amount = amount,
            reason = "message",
            channelId = event.channel.idLong
        )
    }

    companion object {
        // Per-user cooldown between message-XP grants. Matches the
        // industry-standard 60s window so chat-active users still earn
        // XP at a reasonable clip without rewarding one-word spam.
        const val MESSAGE_XP_COOLDOWN_SECONDS: Long = 60L

        // Inclusive bounds on the per-message XP grant. Randomising in a
        // range keeps progression less mechanical.
        const val MESSAGE_XP_MIN: Long = 15L
        const val MESSAGE_XP_MAX: Long = 25L
    }
}
