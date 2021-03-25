package toby.jpa.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
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
    public List<BrotherDto> listBrothers() {
        return brotherService.listBrothers();
    }

    @Override
    public Long createNewBrother(BrotherDto brotherDto) {
        return brotherService.createNewBrother(brotherDto);
    }

    @Override
    public BrotherDto getBrotherById(Long discordId) {
        return brotherService.getBrotherById(discordId);
    }

    @Override
    public BrotherDto getUserByName(String name) {
        return brotherService.getUserByName(name);
    }
}
