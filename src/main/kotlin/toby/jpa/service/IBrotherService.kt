package toby.jpa.service

import toby.jpa.dto.BrotherDto

interface IBrotherService {
    fun listBrothers(): Iterable<BrotherDto?>?
    fun createNewBrother(brotherDto: BrotherDto): BrotherDto?
    fun getBrotherById(discordId: Long?): BrotherDto?
    fun updateBrother(brotherDto: BrotherDto?): BrotherDto?
    fun deleteBrother(brotherDto: BrotherDto?)
    fun deleteBrotherById(discordId: Long?)
    fun clearCache()
}
