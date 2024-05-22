package toby.jpa.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import toby.jpa.dto.ExcuseDto;
import toby.jpa.persistence.IExcusePersistence;
import toby.jpa.service.IExcuseService;

import java.util.List;

@Service
public class ExcuseServiceImpl implements IExcuseService {

    @Autowired
    IExcusePersistence excuseService;

    @Override
    @Cacheable(value = "excuses")
    public List<ExcuseDto> listAllGuildExcuses(Long guildId) {
        return excuseService.listAllGuildExcuses(guildId);
    }

    @Override
    @Cacheable(value = "excuses")
    public List<ExcuseDto> listApprovedGuildExcuses(Long guildId) {
        return excuseService.listApprovedGuildExcuses(guildId);
    }

    @Override
    public List<ExcuseDto> listPendingGuildExcuses(Long guildId) {
        return excuseService.listPendingGuildExcuses(guildId);
    }

    @Override
    @CachePut(value = "excuses", key = "#excuseDto.id")
    public ExcuseDto createNewExcuse(ExcuseDto excuseDto) {
        return excuseService.createNewExcuse(excuseDto);
    }

    @Override
    @CachePut(value = "excuses", key = "#id")
    public ExcuseDto getExcuseById(Integer id) {
        return excuseService.getExcuseById(id);
    }

    @Override
    @CachePut(value = "excuses", key = "#excuseDto.id")
    public ExcuseDto updateExcuse(ExcuseDto excuseDto) {
        return excuseService.updateExcuse(excuseDto);
    }

    @Override
    @CacheEvict(value = "excuses", allEntries = true)
    public void deleteExcuseByGuildId(Long guildId) {
        excuseService.deleteAllExcusesForGuild(guildId);
    }

    @Override
    @CacheEvict(value = "excuses", key = "#id")
    public void deleteExcuseById(Integer id) {
        excuseService.deleteExcuseById(id);
    }

    @Override
    @CacheEvict(value = "excuses", allEntries = true)
    public void clearCache() {

    }


}
