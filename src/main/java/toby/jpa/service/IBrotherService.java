package toby.jpa.service;

import toby.jpa.dto.BrotherDto;

import java.util.List;

public interface IBrotherService {

    List<BrotherDto> listBrothers();
    BrotherDto createNewBrother(BrotherDto brotherDto);
    BrotherDto getBrotherById(Long discordId);
    BrotherDto getUserByName(String name);
    BrotherDto updateBrother(BrotherDto brotherDto);
    void deleteBrother(BrotherDto brotherDto);
    void deleteBrotherById(long discordId);

}
