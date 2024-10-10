package bot.controller

import database.dto.BrotherDto
import database.dto.ConfigDto
import database.dto.UserDto
import database.service.IBrotherService
import database.service.IConfigService
import database.service.IMusicFileService
import database.service.IUserService
import org.springframework.web.bind.annotation.*

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
