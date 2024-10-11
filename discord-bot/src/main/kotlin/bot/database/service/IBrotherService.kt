package bot.database.service

interface IBrotherService {
    fun listBrothers(): Iterable<bot.database.dto.BrotherDto?>?
    fun createNewBrother(brotherDto: bot.database.dto.BrotherDto): bot.database.dto.BrotherDto?
    fun getBrotherById(discordId: Long?): bot.database.dto.BrotherDto?
    fun updateBrother(brotherDto: bot.database.dto.BrotherDto?): bot.database.dto.BrotherDto?
    fun deleteBrother(brotherDto: bot.database.dto.BrotherDto?)
    fun deleteBrotherById(discordId: Long?)
    fun clearCache()
}
