package database.dto.lottery

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.LocalDate

/**
 * Per-(guild, user) daily-lottery participation streak.
 *
 * One row per user per guild: [streakDays] counts consecutive UTC
 * calendar days on which the user bought at least one lottery ticket
 * (either mode); [lastParticipationDate] is the most recent such day.
 * A buy on the day after [lastParticipationDate] extends the streak; a
 * gap resets it to 1. Same-day repeat buys are no-ops.
 *
 * Read/written inside the buy transaction by
 * [database.service.lottery.JackpotLotteryService] — see
 * V51__lottery_low_engagement.sql.
 */
@Entity
@Table(name = "toby_coin_lottery_streak", schema = "public")
@IdClass(LotteryStreakId::class)
@Transactional
class LotteryStreakDto(
    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Id
    @Column(name = "discord_id")
    var discordId: Long = 0,

    @Column(name = "streak_days", nullable = false)
    var streakDays: Int = 0,

    @Column(name = "last_participation_date")
    var lastParticipationDate: LocalDate? = null,
) : Serializable

data class LotteryStreakId(
    var guildId: Long = 0,
    var discordId: Long = 0,
) : Serializable
