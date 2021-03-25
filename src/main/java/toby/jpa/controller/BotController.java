//package toby.jpa.controller;
//
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RequestParam;
//import org.springframework.web.bind.annotation.RestController;
//import toby.jpa.dto.BrotherDto;
//import toby.jpa.dto.ConfigDto;
//import toby.jpa.service.IBrotherService;
//import toby.jpa.service.IConfigService;
//
////I don't want a rest controller atm, but useful to have in future maybe so will push commented out
//@RestController
//public class BotController {
//
//    @Autowired
//    public IConfigService configService;
//
//    @Autowired
//    public IBrotherService brotherService;
//
//    @RequestMapping("/")
//    public String index() {
//        return "Greetings from Spring Boot!";
//    }
//
//    @RequestMapping("/brother")
//    public BrotherDto getBrother(@RequestParam("discord_id") String discordId){
//
//        return brotherService.getUserByName(discordId);
//    }
//
//    @RequestMapping("/config")
//    public ConfigDto getConfig(@RequestParam("name") String name){
//
//        return configService.getConfigByName(name);
//    }
//
//}
