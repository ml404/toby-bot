package toby.jpa.service.impl

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import toby.jpa.dto.BrotherDto
import toby.jpa.persistence.IBrotherPersistence
import toby.jpa.service.IBrotherService

@Service
open class BrotherServiceImpl : IBrotherService {
    @Autowired
    lateinit var brotherService: IBrotherPersistence

    @Cacheable(value = ["brothers"])
    override fun listBrothers(): Iterable<BrotherDto?> {
        return brotherService.listBrothers()
    }

    @CachePut(value = ["brothers"], key = "#brotherDto.discordId")
    override fun createNewBrother(brotherDto: BrotherDto): BrotherDto? {
        return brotherService.createNewBrother(brotherDto)
    }

    @Cacheable(value = ["brothers"], key = "#discordId")
    override fun getBrotherById(discordId: Long?): BrotherDto? {
        return brotherService.getBrotherById(discordId)
    }

    @CachePut(value = ["brothers"], key = "#brotherDto.discordId")
    override fun updateBrother(brotherDto: BrotherDto?): BrotherDto? {
        return brotherService.updateBrother(brotherDto)
    }

    @CacheEvict(value = ["brothers"], key = "#brotherDto.discordId")
    override fun deleteBrother(brotherDto: BrotherDto?) {
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
