package toby.jpa.persistence;

import toby.jpa.dto.BrotherDto;

import java.util.List;

public interface IBrotherPersistence {
    public List<BrotherDto> listBrothers();
    public Long createNewBrother(BrotherDto brotherDto);
    public BrotherDto getBrotherById(Long discordId);
    public BrotherDto getUserByName(String name);

}
