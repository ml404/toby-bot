package database.persistence

interface BrotherPersistence {
    fun listBrothers(): List<database.dto.BrotherDto?>
    fun createNewBrother(brotherDto: database.dto.BrotherDto): database.dto.BrotherDto?
    fun getBrotherById(discordId: Long?): database.dto.BrotherDto?
    fun getUserByName(name: String?): database.dto.BrotherDto
    fun updateBrother(brotherDto: database.dto.BrotherDto?): database.dto.BrotherDto?
    fun deleteBrother(brotherDto: database.dto.BrotherDto?)
    fun deleteBrotherById(discordId: Long?)
}
