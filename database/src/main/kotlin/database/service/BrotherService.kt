package database.service

import database.dto.BrotherDto

interface BrotherService {
    fun listBrothers(): List<BrotherDto?>
    fun createNewBrother(brotherDto: BrotherDto): BrotherDto?
    fun getBrotherById(discordId: Long?): BrotherDto?
    fun updateBrother(brotherDto: BrotherDto?): BrotherDto?
    fun deleteBrother(brotherDto: BrotherDto?)
    fun deleteBrotherById(discordId: Long?)
    fun clearCache()
}
