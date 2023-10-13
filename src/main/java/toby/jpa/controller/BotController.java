package toby.jpa.controller;

import org.springframework.web.bind.annotation.*;
import toby.jpa.dto.BrotherDto;
import toby.jpa.dto.ConfigDto;
import toby.jpa.dto.UserDto;
import toby.jpa.service.IBrotherService;
import toby.jpa.service.IConfigService;
import toby.jpa.service.IMusicFileService;
import toby.jpa.service.IUserService;

@RestController
@RequestMapping("/")
public class BotController {

    public IUserService userService;

    public IMusicFileService musicFileService;

    public IConfigService configService;

    public IBrotherService brotherService;

    public BotController(IUserService userService, IMusicFileService musicFileService, IConfigService configService, IBrotherService brotherService) {
        this.userService = userService;
        this.musicFileService = musicFileService;
        this.configService = configService;
        this.brotherService = brotherService;
    }

    @GetMapping("/")
    public String index() {
        return "Welcome to TobyBot \n" +
                "To find out more, please visit https://github.com/ml404/toby-bot#readme";
    }

    @GetMapping("/brother")
    @ResponseBody
    public BrotherDto getBrother(@RequestParam("discordId") String discordId){

        return brotherService.getUserByName(discordId);
    }

    @GetMapping("/config")
    @ResponseBody
    public ConfigDto getConfig(@RequestParam("name") String name, @RequestParam("guildId") String guildId){

        return configService.getConfigByName(name, guildId);
    }


    @GetMapping("/music")
    @ResponseBody
    public byte[] getMusicBlob(@RequestParam("id") String id){

        return musicFileService.getMusicFileById(id).getMusicBlob();
    }

    @GetMapping("/user")
    @ResponseBody
    public UserDto getUser(@RequestParam("discordId") Long discordId, @RequestParam("guildId") Long guildId){

        return userService.getUserById(discordId, guildId);
    }

}
