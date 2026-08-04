package web.controller

import common.media.MediaToken
import database.dto.guild.ConfigDto
import database.service.social.BrotherService
import database.service.guild.ConfigService
import database.service.music.MusicFileService
import database.service.user.UserService
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
    fun getBrother(@RequestParam("discordId") discordId: String): database.dto.social.BrotherDto? =
        brotherService.getBrotherById(discordId.toLong())

    @GetMapping("/config")
    @ResponseBody
    fun getConfig(@RequestParam("name") name: String?, @RequestParam("guildId") guildId: String): ConfigDto? = configService.getConfigByName(name, guildId)


    /**
     * Serves an intro's stored audio.
     *
     * Anonymous by necessity — lavaplayer fetches this with no session when it
     * plays a file intro — so access is gated on a signature instead. Without
     * it, ids of the form `guildId_discordId_index` made every uploaded MP3 in
     * every server an unauthenticated, unmetered read for anyone who could
     * read a Discord snowflake. See [MediaToken].
     */
    @GetMapping("/music")
    fun getMusicBlob(
        @RequestParam("id") id: String,
        @RequestParam(value = MediaToken.EXPIRY_PARAM, required = false) expiresAt: Long? = null,
        @RequestParam(value = MediaToken.SIGNATURE_PARAM, required = false) signature: String? = null,
        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String? = null
    ): ResponseEntity<ByteArray> {
        // 404 rather than 403: an unsigned request shouldn't be able to tell
        // an id that exists from one that doesn't.
        if (!MediaToken.isValid(id, expiresAt, signature)) return ResponseEntity.notFound().build()

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
    ): database.dto.user.UserDto? = userService.getUserById(discordId, guildId)
}
