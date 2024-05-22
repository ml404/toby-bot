package toby.jpa.persistence

import toby.jpa.dto.BrotherDto

interface IBrotherPersistence {
    fun listBrothers(): List<BrotherDto?>
    fun createNewBrother(brotherDto: BrotherDto): BrotherDto?
    fun getBrotherById(discordId: Long?): BrotherDto?
    fun getUserByName(name: String?): BrotherDto
    fun updateBrother(brotherDto: BrotherDto?): BrotherDto?
    fun deleteBrother(brotherDto: BrotherDto?)
    fun deleteBrotherById(discordId: Long?)
}
