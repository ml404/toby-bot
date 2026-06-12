package database.dto.economy

import common.economy.Coin
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable

/**
 * Composite key for [UserCoinHoldingDto]: a balance per (user, guild, coin).
 */
data class UserCoinHoldingId(
    var discordId: Long = 0,
    var guildId: Long = 0,
    var coin: String = Coin.DEFAULT.symbol,
) : Serializable

/**
 * A user's balance of a single NON-TOBY coin in one guild. TOBY balances
 * deliberately live in `user.toby_coins` instead (the rest of the bot
 * settles in TOBY); everything else a user holds is a row here.
 */
@Entity
@IdClass(UserCoinHoldingId::class)
@Table(name = "user_coin_holding", schema = "public")
@Transactional
class UserCoinHoldingDto(
    @Id
    @Column(name = "discord_id")
    var discordId: Long = 0,

    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Id
    @Column(name = "coin", nullable = false, length = 16)
    var coin: String = Coin.DEFAULT.symbol,

    @Column(name = "amount", nullable = false)
    var amount: Long = 0,
) : Serializable
