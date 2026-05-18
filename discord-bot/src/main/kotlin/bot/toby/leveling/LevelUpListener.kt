package bot.toby.leveling

import bot.toby.notify.NotificationRouter
import common.events.LevelUpEvent
import common.leveling.LevelCurve
import common.logging.DiscordLogger
import common.notification.ChannelRouteKey
import database.dto.TitleDto
import database.service.LevelRoleRewardService
import database.service.TitleService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.awt.Color

/**
 * Consumer of [LevelUpEvent]. On a level-up:
 *   1. Posts a short congratulatory message in the channel where the XP
 *      was earned (or [LEVEL_UP_CHANNEL] / system channel for voice-only
 *      events with no originating channel).
 *   2. Assigns every configured role reward in `(oldLevel, newLevel]`.
 *   3. Inserts ownership rows for any titles gated at `required_level`
 *      values in `(oldLevel, newLevel]` so the user can equip them free.
 *
 * All Discord-side work is dispatched non-blockingly via JDA `.queue(...)`,
 * so the synchronous event delivery doesn't stall the XP-award caller.
 */
@Service
class LevelUpListener @Autowired constructor(
    @Lazy private val jda: JDA,
    private val levelRoleRewardService: LevelRoleRewardService,
    private val titleService: TitleService,
    private val notificationRouter: NotificationRouter,
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    @EventListener
    fun onLevelUp(event: LevelUpEvent) {
        val guild = jda.getGuildById(event.guildId) ?: run {
            logger.warn("LevelUpEvent for guild ${event.guildId} but JDA has no matching guild; skipping.")
            return
        }
        logger.setGuildContext(guild)
        logger.info {
            "User ${event.discordId} levelled up: ${event.oldLevel} -> ${event.newLevel}"
        }
        announce(guild, event)
        assignRoleRewards(guild, event)
        unlockTitles(event)
    }

    private fun announce(guild: Guild, event: LevelUpEvent) {
        val member = runCatching { guild.getMemberById(event.discordId) }.getOrNull()
        notificationRouter.sendChannel(
            guildId = guild.idLong,
            route = ChannelRouteKey.LEVEL_UP,
            originChannelId = event.channelId,
            message = {
                MessageCreateBuilder().setEmbeds(buildLevelUpEmbed(event, member)).build()
            },
        )
    }

    private fun buildLevelUpEmbed(event: LevelUpEvent, member: Member?): MessageEmbed {
        val achievedXp = LevelCurve.xpForNextLevel((event.newLevel - 1).coerceAtLeast(0))
        val mention = "<@${event.discordId}>"
        val builder = EmbedBuilder()
            .setTitle("Level Up — LVL ${event.newLevel}")
            .setDescription("GG $mention — welcome to **Level ${event.newLevel}**!")
            .setColor(Color(tierColor(event.newLevel)))
            .addField(
                "Progress",
                "`${progressBar(achievedXp, achievedXp)}` " +
                    "${formatXp(achievedXp)} / ${formatXp(achievedXp)} XP",
                false
            )
            .addField("Total XP", formatXp(event.totalXp), true)
            .setFooter("${tierName(event.newLevel)} tier")
        member?.let { m ->
            val avatar = runCatching { m.effectiveAvatarUrl }.getOrNull()
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            builder.setAuthor(m.effectiveName, null, avatar)
        }
        return builder.build()
    }

    private fun tierColor(level: Int): Int = when {
        level >= 200 -> 0xFFD700 // Legendary
        level >= 150 -> 0xFF3CAC // Mythic
        level >= 100 -> 0x9B30FF // Master
        level >= 75 -> 0x5865F2  // Diamond
        level >= 50 -> 0xE5E4E2  // Platinum
        level >= 25 -> 0xD4A017  // Gold
        level >= 10 -> 0x9AA3B2  // Silver
        else -> 0xC9803A         // Bronze
    }

    private fun tierName(level: Int): String = when {
        level >= 200 -> "Legendary"
        level >= 150 -> "Mythic"
        level >= 100 -> "Master"
        level >= 75 -> "Diamond"
        level >= 50 -> "Platinum"
        level >= 25 -> "Gold"
        level >= 10 -> "Silver"
        else -> "Bronze"
    }

    private fun progressBar(filled: Long, total: Long, cells: Int = 10): String {
        if (total <= 0L) return "░".repeat(cells)
        val ratio = (filled.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
        val full = (ratio * cells).toInt().coerceIn(0, cells)
        return "█".repeat(full) + "░".repeat(cells - full)
    }

    private fun formatXp(value: Long): String = String.format("%,d", value)

    private fun assignRoleRewards(guild: Guild, event: LevelUpEvent) {
        val rewards = levelRoleRewardService.listInRange(
            guildId = event.guildId,
            fromExclusive = event.oldLevel,
            toInclusive = event.newLevel
        )
        if (rewards.isEmpty()) return
        // UserSnowflake lets us issue the add-role REST call without
        // pre-fetching the member; JDA handles the 404 if they've left.
        val target = UserSnowflake.fromId(event.discordId)
        rewards.forEach { reward ->
            val role: Role? = guild.getRoleById(reward.roleId)
            if (role == null) {
                logger.warn("Configured level-${reward.level} role ${reward.roleId} missing in guild ${guild.idLong}.")
                return@forEach
            }
            runCatching {
                guild.addRoleToMember(target, role).queue(null) { err ->
                    logger.warn("Failed to assign role ${role.name} to ${event.discordId}: ${err.message}")
                }
            }.onFailure {
                logger.warn("Role assignment dispatch failed for level ${reward.level}: ${it.message}")
            }
        }
    }

    private fun unlockTitles(event: LevelUpEvent) {
        val titles = titleService.listAll()
            .filter { it.requiredLevel > event.oldLevel && it.requiredLevel <= event.newLevel }
        if (titles.isEmpty()) return
        titles.forEach { title -> unlockOne(event.discordId, title) }
    }

    private fun unlockOne(discordId: Long, title: TitleDto) {
        val titleId = title.id ?: return
        if (titleService.owns(discordId, titleId)) return
        runCatching { titleService.recordPurchase(discordId, titleId) }
            .onFailure {
                logger.warn("Failed to auto-unlock title '${title.label}' for user $discordId: ${it.message}")
            }
    }
}
