package web.controller

import database.dto.ConfigDto
import database.service.BrotherService
import database.service.ConfigService
import database.service.MusicFileService
import database.service.UserService
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.concurrent.TimeUnit

@RestController
@RequestMapping("/")
class BotController(
    private val userService: UserService,
    private val musicFileService: MusicFileService,
    private val configService: ConfigService,
    private val brotherService: BrotherService
) {
    @GetMapping("/brother")
    @ResponseBody
    fun getBrother(@RequestParam("discordId") discordId: String): database.dto.BrotherDto? =
        brotherService.getBrotherById(discordId.toLong())

    @GetMapping("/config")
    @ResponseBody
    fun getConfig(@RequestParam("name") name: String?, @RequestParam("guildId") guildId: String): ConfigDto? = configService.getConfigByName(name, guildId)


    @GetMapping("/music")
    fun getMusicBlob(
        @RequestParam("id") id: String,
        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String? = null
    ): ResponseEntity<ByteArray> {
        val dto = musicFileService.getMusicFileById(id) ?: return ResponseEntity.notFound().build()
        val blob = dto.musicBlob ?: return ResponseEntity.notFound().build()

        // Use hash as ETag when available; fall back to id.
        val hash = runCatching { dto.musicBlobHash }.getOrNull()
        val etag = "\"${hash ?: id}\""
        val cache = CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate().mustRevalidate()

        if (ifNoneMatch != null && ifNoneMatch == etag) {
            return ResponseEntity.status(304).eTag(etag).cacheControl(cache).build()
        }

        return ResponseEntity.ok()
            .eTag(etag)
            .cacheControl(cache)
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
