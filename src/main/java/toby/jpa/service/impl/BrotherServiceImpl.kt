package toby.jpa.service.impl;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import toby.jpa.dto.BrotherDto;
import toby.jpa.persistence.IBrotherPersistence;
import toby.jpa.service.IBrotherService;

import java.util.Optional;

@Service
public class BrotherServiceImpl implements IBrotherService {

    IBrotherPersistence brotherService;

    public BrotherServiceImpl(IBrotherPersistence brotherService) {
        this.brotherService = brotherService;
    }

    @Override
    @Cacheable(value = "brothers")
    public Iterable<BrotherDto> listBrothers() {
        return brotherService.listBrothers();
    }

    @Override
    @CachePut(value = "brothers", key = "#brotherDto.discordId")
    public BrotherDto createNewBrother(BrotherDto brotherDto) {
        return brotherService.createNewBrother(brotherDto);
    }

    @Override
    @Cacheable(value = "brothers", key = "#discordId")
    public Optional<BrotherDto> getBrotherById(Long discordId) {
        return Optional.ofNullable(brotherService.getBrotherById(discordId));
    }

    @Override
    @CachePut(value = "brothers", key = "#brotherDto.discordId")
    public BrotherDto updateBrother(BrotherDto brotherDto) {
        return brotherService.updateBrother(brotherDto);
    }

    @Override
    @CacheEvict(value = "brothers", key = "#brotherDto.discordId")
    public void deleteBrother(BrotherDto brotherDto) {
        brotherService.deleteBrother(brotherDto);
    }

    @Override
    @CacheEvict(value = "brothers", key = "#discordId")
    public void deleteBrotherById(Long discordId) {
        brotherService.deleteBrotherById(discordId);
    }

    @CacheEvict(value = "brothers", allEntries = true)
    public void clearCache() {
    }

}
