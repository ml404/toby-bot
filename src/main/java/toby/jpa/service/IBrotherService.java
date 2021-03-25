package toby.jpa.service;

import toby.jpa.dto.BrotherDto;

import java.util.List;

public interface IBrotherService {

    public List<BrotherDto> listBrothers();
    public Long createNewBrother(BrotherDto brotherDto);
    public BrotherDto getBrotherById(Long discordId);
    public BrotherDto getUserByName(String name);

}
