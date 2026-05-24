package common.events.lottery

/**
 * Fact emitted by `LotteryAnnouncer.announceCycle` for every winner of
 * a daily draw. Mirrors the semantics of the existing
 * [common.notification.NotificationChannelKind.LOTTERY_DRAW_WITH_MY_TICKET]
 * dispatch (which today fires only for winners — see `LotteryAnnouncer`).
 *
 * Subscribers can use [didWin] to differentiate "you won X" toasts from
 * a future "your draw resolved" toast; today every published event has
 * `didWin = true` and `amountWon > 0`, but the field is here so a
 * future extension to all ticket holders is a no-op for subscribers.
 */
data class LotteryDrawnForTicketHolderEvent(
    val discordId: Long,
    val guildId: Long,
    val didWin: Boolean,
    val amountWon: Long,
)
