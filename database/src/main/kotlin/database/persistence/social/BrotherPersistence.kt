package database.persistence.social

interface BrotherPersistence {
    fun listBrothers(): List<database.dto.social.BrotherDto?>
    fun createNewBrother(brotherDto: database.dto.social.BrotherDto): database.dto.social.BrotherDto?
    fun getBrotherById(discordId: Long?): database.dto.social.BrotherDto?
    fun getUserByName(name: String?): database.dto.social.BrotherDto
    fun updateBrother(brotherDto: database.dto.social.BrotherDto?): database.dto.social.BrotherDto?
    fun deleteBrother(brotherDto: database.dto.social.BrotherDto?)
    fun deleteBrotherById(discordId: Long?)
}
