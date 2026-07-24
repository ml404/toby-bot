package database.persistence.lottery

import database.dto.lottery.LotteryStreakDto

interface LotteryStreakPersistence {
    /**
     * SELECT … FOR UPDATE on the (guild, user) streak row, or null when
     * the user has never participated. Requires @Transactional — called
     * from inside the buy transaction so two simultaneous buys can't
     * double-count the same day.
     */
    fun getForUpdate(guildId: Long, discordId: Long): LotteryStreakDto?

    fun upsert(streak: LotteryStreakDto): LotteryStreakDto
}
