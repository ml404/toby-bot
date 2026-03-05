package web.controller

import database.dto.ConfigDto
import database.service.BrotherService
import database.service.ConfigService
import database.service.MusicFileService
import database.service.UserService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
    fun index(): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, "/login")
            .build()

    @GetMapping("/brother")
    @ResponseBody
    fun getBrother(@RequestParam("discordId") discordId: String): database.dto.BrotherDto? =
        brotherService.getBrotherById(discordId.toLong())

    @GetMapping("/config")
    @ResponseBody
    fun getConfig(@RequestParam("name") name: String?, @RequestParam("guildId") guildId: String): ConfigDto? = configService.getConfigByName(name, guildId)


    @GetMapping("/music")
    fun getMusicBlob(@RequestParam("id") id: String): ResponseEntity<ByteArray> {
        val blob = musicFileService.getMusicFileById(id)?.musicBlob
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("audio/mpeg"))
            .body(blob)
    }


    @GetMapping("/user")
    @ResponseBody
    fun getUser(
        @RequestParam("discordId") discordId: Long?,
        @RequestParam("guildId") guildId: Long?
    ): database.dto.UserDto? = userService.getUserById(discordId, guildId)
}
