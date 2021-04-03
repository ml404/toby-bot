package toby.jpa.persistence;

import toby.jpa.dto.BrotherDto;

import java.util.List;

public interface IBrotherPersistence {
    List<BrotherDto> listBrothers();
    BrotherDto createNewBrother(BrotherDto brotherDto);
    BrotherDto getBrotherById(Long discordId);
    BrotherDto getUserByName(String name);
    BrotherDto updateBrother(BrotherDto brotherDto);
    void deleteBrother(BrotherDto brotherDto);
    void deleteBrotherById(long discordId);
}
