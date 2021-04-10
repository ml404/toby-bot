package toby.jpa.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import toby.jpa.dto.BrotherDto;
import toby.jpa.persistence.IBrotherPersistence;
import toby.jpa.service.IBrotherService;

import java.util.List;

@Service
public class BrotherServiceImpl implements IBrotherService {

    @Autowired
    IBrotherPersistence brotherService;

    @Override
    @CacheEvict(value = "brothers", allEntries = true)
    public List<BrotherDto> listBrothers() {
        return brotherService.listBrothers();
    }

    @Override
    @CachePut(value="brothers", key = "#brotherDto.discordId")
    public BrotherDto createNewBrother(BrotherDto brotherDto) {
        return brotherService.createNewBrother(brotherDto);
    }

    @Override
    @Cacheable(value = "brothers", key = "#discordId")
    public BrotherDto getBrotherById(Long discordId) {
        return brotherService.getBrotherById(discordId);
    }

    @Override
    @Cacheable(value = "brothers", key = "#name")
    public BrotherDto getUserByName(String name) {
        return brotherService.getUserByName(name);
    }

    @Override
    @CacheEvict(value = "brothers", key = "#brotherDto.discordId")
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
    public void deleteBrotherById(long discordId) {
        brotherService.deleteBrotherById(discordId);
    }


}
