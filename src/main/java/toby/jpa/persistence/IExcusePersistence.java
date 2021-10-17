package toby.jpa.persistence;

import toby.jpa.dto.ExcuseDto;

import java.util.List;

public interface IExcusePersistence {

    List<ExcuseDto> listAllGuildExcuses(Long guildId);
    List<ExcuseDto> listApprovedGuildExcuses(Long guildId);
    List<ExcuseDto> listPendingGuildExcuses(Long guildId);
    ExcuseDto createNewExcuse(ExcuseDto excuseDto);
    ExcuseDto getExcuseById(Integer id);
    ExcuseDto updateExcuse(ExcuseDto excuseDto);
    void deleteAllExcusesForGuild(Long excuseDto);
    void deleteExcuseById(Integer discordId);
}
