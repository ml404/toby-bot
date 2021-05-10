package toby.jpa.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import toby.jpa.dto.BrotherDto;
import toby.jpa.dto.ConfigDto;
import toby.jpa.dto.MusicDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IBrotherService;
import toby.jpa.service.IConfigService;
import toby.jpa.service.IMusicFileService;
import toby.jpa.service.IUserService;

@RestController
public class BotController {

    @Autowired
    public IUserService userService;

    @Autowired
    public IMusicFileService musicFileService;

    @Autowired
    public IConfigService configService;

    @Autowired
    public IBrotherService brotherService;

    @RequestMapping("/")
    public String index() {
        return "Welcome to TobyBot";
    }

    @RequestMapping("/brother/{discordId}")
    public BrotherDto getBrother(@RequestParam("discordId") String discordId){

        return brotherService.getUserByName(discordId);
    }

    @RequestMapping("/config/{guildId}/{name}")
    public ConfigDto getConfig(@RequestParam("name") String name, @RequestParam("guildId") String guildId){

        return configService.getConfigByName(name, guildId);
    }


    @RequestMapping("/music/{id}")
    public MusicDto getMusic(@RequestParam("id") String id){

        return musicFileService.getMusicFileById(id);
    }

    @RequestMapping("/user/{guildId}/{discordId}")
    public UserDto getUser(@RequestParam("discordId") Long discordId, @RequestParam("guildId") Long guildId){

        return userService.getUserById(discordId, guildId);
    }

}
