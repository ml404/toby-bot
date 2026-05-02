package database.service

import database.dto.TipDailyDto
import database.dto.TipLogDto
import database.persistence.TipLogPersistence
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Atomic peer-to-peer transfer of social credit. Daily outgoing cap is
 * tracked separately from the voice/command earn cap — tips are
 * transfers, not earnings, so they must not consume the daily-earn
 * bucket and must not let an alt funnel its capped earnings to a main.
 *
 * Both user rows are locked in ascending discord-id order to avoid
 * deadlocks against a concurrent reverse-direction tip. The
 * `tip_daily` row for the sender is locked after the user rows; only
 * the sender ever touches their own row, so there is no cross-tip
 * deadlock to worry about there.
 */
@Service
@Transactional
class TipService @Autowired constructor(
    private val userService: UserService,
    private val tipDailyService: TipDailyService,
    private val tipLogPersistence: TipLogPersistence,
    private val jackpotService: JackpotService,
) {
    sealed interface TipOutcome {
        data class Ok(
            val sender: Long,
            val recipient: Long,
            val amount: Long,
            val note: String?,
            val senderNewBalance: Long,
            val recipientNewBalance: Long,
            val sentTodayAfter: Long,
            val dailyCap: Long,
            val jackpotPool: Long = 0L
        ) : TipOutcome

        data class InvalidAmount(val min: Long, val max: Long) : TipOutcome
        data class InvalidRecipient(val reason: Reason) : TipOutcome {
            enum class Reason { SELF, BOT, MISSING }
        }
        data class InsufficientCredits(val have: Long, val needed: Long) : TipOutcome
        data class DailyCapExceeded(val sentToday: Long, val cap: Long, val attempted: Long) : TipOutcome
        data object UnknownSender : TipOutcome
        data object UnknownRecipient : TipOutcome
    }

    fun tip(
        senderDiscordId: Long,
        recipientDiscordId: Long,
        guildId: Long,
        amount: Long,
        note: String? = null,
        at: Instant = Instant.now(),
        dailyCap: Long = DEFAULT_TIP_DAILY_CAP,
    ): TipOutcome {
        if (amount !in MIN_TIP..MAX_TIP) {
            return TipOutcome.InvalidAmount(MIN_TIP, MAX_TIP)
        }
        if (senderDiscordId == recipientDiscordId) {
            return TipOutcome.InvalidRecipient(TipOutcome.InvalidRecipient.Reason.SELF)
        }

        // Lock both user rows in deterministic order to prevent A→B vs B→A deadlocks.
        val (sender, recipient) = lockBoth(senderDiscordId, recipientDiscordId, guildId)
        sender ?: return TipOutcome.UnknownSender
        recipient ?: return TipOutcome.UnknownRecipient

        val today = LocalDate.ofInstant(at, ZoneOffset.UTC)
        val existingDaily = tipDailyService.get(senderDiscordId, guildId, today)
        val sentToday = existingDaily?.creditsSent ?: 0L
        if (sentToday + amount > dailyCap) {
            return TipOutcome.DailyCapExceeded(sentToday, dailyCap, amount)
        }

        val senderBalance = sender.socialCredit ?: 0L
        if (senderBalance < amount) {
            return TipOutcome.InsufficientCredits(senderBalance, amount)
        }

        val recipientBalance = recipient.socialCredit ?: 0L
        sender.socialCredit = senderBalance - amount
        recipient.socialCredit = recipientBalance + amount
        userService.updateUser(sender)
        userService.updateUser(recipient)

        val newDailyTotal = sentToday + amount
        tipDailyService.upsert(
            TipDailyDto(
                senderDiscordId = senderDiscordId,
                guildId = guildId,
                tipDate = today,
                creditsSent = newDailyTotal
            )
        )

        val truncatedNote = note?.take(MAX_NOTE_LENGTH)
        tipLogPersistence.insert(
            TipLogDto(
                guildId = guildId,
                senderDiscordId = senderDiscordId,
                recipientDiscordId = recipientDiscordId,
                amount = amount,
                note = truncatedNote,
                createdAt = at
            )
        )

        return TipOutcome.Ok(
            sender = senderDiscordId,
            recipient = recipientDiscordId,
            amount = amount,
            note = truncatedNote,
            senderNewBalance = senderBalance - amount,
            recipientNewBalance = recipientBalance + amount,
            sentTodayAfter = newDailyTotal,
            dailyCap = dailyCap,
            jackpotPool = jackpotService.getPool(guildId)
        )
    }

    private fun lockBoth(aId: Long, bId: Long, guildId: Long): Pair<database.dto.UserDto?, database.dto.UserDto?> {
        return if (aId < bId) {
            val a = userService.getUserByIdForUpdate(aId, guildId)
            val b = userService.getUserByIdForUpdate(bId, guildId)
            a to b
        } else {
            val b = userService.getUserByIdForUpdate(bId, guildId)
            val a = userService.getUserByIdForUpdate(aId, guildId)
            a to b
        }
    }

    companion object {
        const val MIN_TIP: Long = 10L
        const val MAX_TIP: Long = 500L
        const val DEFAULT_TIP_DAILY_CAP: Long = 500L
        const val MAX_NOTE_LENGTH: Int = 200
    }
}
