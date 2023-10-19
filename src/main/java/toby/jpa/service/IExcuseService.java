package toby.jpa.service;

import toby.jpa.dto.ExcuseDto;

import java.util.List;

public interface IExcuseService {

    List<ExcuseDto> listAllGuildExcuses(Long guildId);
    List<ExcuseDto> listApprovedGuildExcuses(Long guildId);
    List<ExcuseDto> listPendingGuildExcuses(Long guildId);
    ExcuseDto createNewExcuse(ExcuseDto excuseDto);
    ExcuseDto getExcuseById(Integer id);
    ExcuseDto updateExcuse(ExcuseDto excuseDto);
    void deleteExcuseByGuildId(Long guildId);
    void deleteExcuseById(Integer id);
    void clearCache();

}
