package toby.jpa.controller

import org.springframework.web.bind.annotation.*
import toby.jpa.dto.BrotherDto
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IBrotherService
import toby.jpa.service.IConfigService
import toby.jpa.service.IMusicFileService
import toby.jpa.service.IUserService

@RestController
@RequestMapping("/")
class BotController(
    var userService: IUserService,
    var musicFileService: IMusicFileService,
    var configService: IConfigService,
    var brotherService: IBrotherService
) {
    @GetMapping("/")
    fun index(): String =
         """
            Welcome to TobyBot 
            To find out more, please visit https://github.com/ml404/toby-bot#readme
            """.trimIndent()

    @GetMapping("/brother")
    @ResponseBody
    fun getBrother(@RequestParam("discordId") discordId: String): BrotherDto? = brotherService.getBrotherById(discordId.toLong())

    @GetMapping("/config")
    @ResponseBody
    fun getConfig(@RequestParam("name") name: String?, @RequestParam("guildId") guildId: String): ConfigDto? = configService.getConfigByName(name, guildId)


    @GetMapping("/music")
    @ResponseBody
    fun getMusicBlob(@RequestParam("id") id: String): ByteArray? = musicFileService.getMusicFileById(id)?.musicBlob


    @GetMapping("/user")
    @ResponseBody
    fun getUser(@RequestParam("discordId") discordId: Long?, @RequestParam("guildId") guildId: Long?): UserDto? = userService.getUserById(discordId, guildId)
}
