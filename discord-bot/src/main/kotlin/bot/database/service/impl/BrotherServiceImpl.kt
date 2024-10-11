package bot.database.service.impl

import bot.database.persistence.IBrotherPersistence
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

@Service
open class BrotherServiceImpl : bot.database.service.IBrotherService {
    @Autowired
    lateinit var brotherService: IBrotherPersistence

    @Cacheable(value = ["brothers"])
    override fun listBrothers(): Iterable<bot.database.dto.BrotherDto?> {
        return brotherService.listBrothers()
    }

    @CachePut(value = ["brothers"], key = "#brotherDto.discordId")
    override fun createNewBrother(brotherDto: bot.database.dto.BrotherDto): bot.database.dto.BrotherDto? {
        return brotherService.createNewBrother(brotherDto)
    }

    @Cacheable(value = ["brothers"], key = "#discordId")
    override fun getBrotherById(discordId: Long?): bot.database.dto.BrotherDto? {
        return brotherService.getBrotherById(discordId)
    }

    @CachePut(value = ["brothers"], key = "#brotherDto.discordId")
    override fun updateBrother(brotherDto: bot.database.dto.BrotherDto?): bot.database.dto.BrotherDto? {
        return brotherService.updateBrother(brotherDto)
    }

    @CacheEvict(value = ["brothers"], key = "#brotherDto.discordId")
    override fun deleteBrother(brotherDto: bot.database.dto.BrotherDto?) {
        brotherService.deleteBrother(brotherDto)
    }

    @CacheEvict(value = ["brothers"], key = "#discordId")
    override fun deleteBrotherById(discordId: Long?) {
        brotherService.deleteBrotherById(discordId)
    }

    @CacheEvict(value = ["brothers"], allEntries = true)
    override fun clearCache() {
    }
}
