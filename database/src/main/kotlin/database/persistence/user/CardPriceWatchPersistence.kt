package database.persistence.user

import database.dto.user.CardPriceWatchDto

interface CardPriceWatchPersistence {
    fun save(watch: CardPriceWatchDto): CardPriceWatchDto

    fun findById(id: Long): CardPriceWatchDto?

    fun listEnabled(): List<CardPriceWatchDto>

    fun listByUser(discordId: Long): List<CardPriceWatchDto>

    fun deleteById(id: Long): Boolean
}
