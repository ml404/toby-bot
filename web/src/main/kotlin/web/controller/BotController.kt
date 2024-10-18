package web.controller

import database.dto.ConfigDto
import database.service.BrotherService
import database.service.ConfigService
import database.service.MusicFileService
import database.service.UserService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/")
class BotController(
    var userService: UserService,
    var musicFileService: MusicFileService,
    var configService: ConfigService,
    var brotherService: BrotherService
) {
    @GetMapping("/")
    fun index(): String =
         """
            Welcome to TobyBot 
            To find out more, please visit https://github.com/ml404/toby-bot#readme
            """.trimIndent()

    @GetMapping("/brother")
    @ResponseBody
    fun getBrother(@RequestParam("discordId") discordId: String): database.dto.BrotherDto? =
        brotherService.getBrotherById(discordId.toLong())

    @GetMapping("/config")
    @ResponseBody
    fun getConfig(@RequestParam("name") name: String?, @RequestParam("guildId") guildId: String): ConfigDto? = configService.getConfigByName(name, guildId)


    @GetMapping("/music")
    @ResponseBody
    fun getMusicBlob(@RequestParam("id") id: String): ByteArray? = musicFileService.getMusicFileById(id)?.musicBlob


    @GetMapping("/user")
    @ResponseBody
    fun getUser(
        @RequestParam("discordId") discordId: Long?,
        @RequestParam("guildId") guildId: Long?
    ): database.dto.UserDto? = userService.getUserById(discordId, guildId)
}
