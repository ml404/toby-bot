package bot.database.persistence

interface IBrotherPersistence {
    fun listBrothers(): List<bot.database.dto.BrotherDto?>
    fun createNewBrother(brotherDto: bot.database.dto.BrotherDto): bot.database.dto.BrotherDto?
    fun getBrotherById(discordId: Long?): bot.database.dto.BrotherDto?
    fun getUserByName(name: String?): bot.database.dto.BrotherDto
    fun updateBrother(brotherDto: bot.database.dto.BrotherDto?): bot.database.dto.BrotherDto?
    fun deleteBrother(brotherDto: bot.database.dto.BrotherDto?)
    fun deleteBrotherById(discordId: Long?)
}
