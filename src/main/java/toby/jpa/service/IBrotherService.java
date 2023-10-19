package toby.jpa.service;

import toby.jpa.dto.BrotherDto;

import java.util.Optional;

public interface IBrotherService  {
    Iterable<BrotherDto> listBrothers();
    BrotherDto createNewBrother(BrotherDto brotherDto);
    Optional<BrotherDto> getBrotherById(Long discordId);
    BrotherDto updateBrother(BrotherDto brotherDto);
    void deleteBrother(BrotherDto brotherDto);
    void deleteBrotherById(Long discordId);
    void clearCache();

}
