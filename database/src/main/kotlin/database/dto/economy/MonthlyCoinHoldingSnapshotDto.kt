package database.dto.economy

import common.economy.Coin
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.LocalDate

/**
 * Composite key for [MonthlyCoinHoldingSnapshotDto]: a frozen balance per
 * (user, guild, month-boundary date, coin).
 */
data class MonthlyCoinHoldingSnapshotId(
    var discordId: Long = 0,
    var guildId: Long = 0,
    var snapshotDate: LocalDate = LocalDate.now(),
    var coin: String = Coin.DEFAULT.symbol,
) : Serializable

/**
 * A user's NON-TOBY coin balance frozen at a monthly boundary. Mirrors
 * [UserCoinHoldingDto] with a [snapshotDate] so the wallet leaderboard can
 * diff "now vs the 1st" per coin. TOBY deliberately stays in
 * `monthly_credit_snapshot.toby_coins`; everything else a user held at the
 * boundary is a row here.
 */
@Entity
@IdClass(MonthlyCoinHoldingSnapshotId::class)
@Table(name = "monthly_coin_holding_snapshot", schema = "public")
@Transactional
class MonthlyCoinHoldingSnapshotDto(
    @Id
    @Column(name = "discord_id")
    var discordId: Long = 0,

    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Id
    @Column(name = "snapshot_date")
    var snapshotDate: LocalDate = LocalDate.now(),

    @Id
    @Column(name = "coin", nullable = false, length = 16)
    var coin: String = Coin.DEFAULT.symbol,

    @Column(name = "amount", nullable = false)
    var amount: Long = 0,
) : Serializable
