package database.dto

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.NamedQueries
import jakarta.persistence.NamedQuery
import jakarta.persistence.Table
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.LocalDate

/**
 * Idempotency ledger for the daily match-numbers lottery auto-draw.
 *
 * Mirrors [UbiDailyDto] (V24__ubi_daily.sql): one row per
 * (guild, draw_date). The scheduled job consults [draw_date] before
 * touching anything for that guild — if a row exists for today, the
 * job already ran and the close/open cycle is skipped. Survives
 * mid-cron restarts and accidental double-firing without
 * double-drawing or double-seeding the prize pool.
 */
@NamedQueries(
    NamedQuery(
        name = "LotteryDailyDto.get",
        query = "select d from LotteryDailyDto d " +
                "where d.guildId = :guildId and d.drawDate = :drawDate"
    )
)
@Entity
@Table(name = "toby_coin_jackpot_lottery_daily", schema = "public")
@IdClass(LotteryDailyId::class)
@Transactional
class LotteryDailyDto(
    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Id
    @Column(name = "draw_date")
    var drawDate: LocalDate = LocalDate.now(),
) : Serializable

data class LotteryDailyId(
    var guildId: Long = 0,
    var drawDate: LocalDate = LocalDate.now(),
) : Serializable
