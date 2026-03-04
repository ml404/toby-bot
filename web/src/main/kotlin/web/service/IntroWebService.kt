package web.service

import com.fasterxml.jackson.databind.ObjectMapper
import database.dto.MusicDto
import database.dto.UserDto
import database.service.MusicFileService
import database.service.UserService
import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

@Service
class IntroWebService(
    private val userService: UserService,
    private val musicFileService: MusicFileService,
    private val jda: JDA
) {
    companion object {
        const val MAX_FILE_SIZE = 550 * 1024
        const val MAX_INTRO_COUNT = 3
        private val objectMapper = ObjectMapper()
    }

    fun getMutualGuilds(accessToken: String): List<GuildInfo> {
        val userGuilds = fetchUserGuildsFromDiscord(accessToken)
        return userGuilds.filter { jda.getGuildById(it.id) != null }
    }

    private fun fetchUserGuildsFromDiscord(accessToken: String): List<GuildInfo> {
        val connection = URL("https://discord.com/api/users/@me/guilds").openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.setRequestProperty("Content-Type", "application/json")

        if (connection.responseCode != 200) return emptyList()

        val body = connection.inputStream.bufferedReader().readText()
        return objectMapper.readTree(body).map { node ->
            GuildInfo(
                id = node["id"].asText(),
                name = node["name"].asText(),
                iconHash = node["icon"]?.takeUnless { it.isNull }?.asText()
            )
        }
    }

    fun getGuildName(guildId: Long): String? = jda.getGuildById(guildId)?.name

    fun getOrCreateUser(discordId: Long, guildId: Long): UserDto {
        return userService.getUserById(discordId, guildId)
            ?: UserDto(discordId = discordId, guildId = guildId).also { userService.createNewUser(it) }
    }

    fun getUserIntros(discordId: Long, guildId: Long): List<MusicDto> {
        val user = userService.getUserById(discordId, guildId) ?: return emptyList()
        return user.musicDtos.sortedBy { it.index }
    }

    fun setIntroByUrl(discordId: Long, guildId: Long, url: String, volume: Int, replaceIndex: Int?): String? {
        if (!isValidUrl(url)) return "Invalid URL provided."

        val user = getOrCreateUser(discordId, guildId)
        val existingIntros = user.musicDtos.sortedBy { it.index }

        if (replaceIndex == null && existingIntros.size >= MAX_INTRO_COUNT) {
            return "You already have $MAX_INTRO_COUNT intros. Please select one to replace."
        }

        val selectedDto = replaceIndex?.let { idx -> existingIntros.find { it.index == idx } }

        if (selectedDto != null) {
            selectedDto.fileName = url
            selectedDto.musicBlob = null
            selectedDto.musicBlobHash = null
            selectedDto.introVolume = volume
            musicFileService.updateMusicFile(selectedDto)
        } else {
            val newIndex = existingIntros.size + 1
            val newDto = MusicDto(user, newIndex, url, volume, null)
            musicFileService.createNewMusicFile(newDto)
        }

        return null
    }

    fun setIntroByFile(discordId: Long, guildId: Long, file: MultipartFile, volume: Int, replaceIndex: Int?): String? {
        if (file.isEmpty) return "No file provided."
        if (!file.originalFilename.orEmpty().endsWith(".mp3", ignoreCase = true)) {
            return "Only MP3 files are supported."
        }
        if (file.size > MAX_FILE_SIZE) {
            return "File exceeds maximum size of ${MAX_FILE_SIZE / 1024}KB."
        }

        val user = getOrCreateUser(discordId, guildId)
        val existingIntros = user.musicDtos.sortedBy { it.index }

        if (replaceIndex == null && existingIntros.size >= MAX_INTRO_COUNT) {
            return "You already have $MAX_INTRO_COUNT intros. Please select one to replace."
        }

        val fileBytes = file.bytes
        val fileName = file.originalFilename ?: "intro.mp3"
        val selectedDto = replaceIndex?.let { idx -> existingIntros.find { it.index == idx } }

        if (selectedDto != null) {
            selectedDto.fileName = fileName
            selectedDto.musicBlob = fileBytes
            selectedDto.musicBlobHash = MusicDto.computeHash(fileBytes)
            selectedDto.introVolume = volume
            musicFileService.updateMusicFile(selectedDto)
        } else {
            val newIndex = existingIntros.size + 1
            val newDto = MusicDto(user, newIndex, fileName, volume, fileBytes)
            musicFileService.createNewMusicFile(newDto)
                ?: return "This file already exists as one of your intros."
        }

        return null
    }

    fun deleteIntro(discordId: Long, guildId: Long, introId: String): String? {
        val expectedPrefix = "${guildId}_${discordId}_"
        if (!introId.startsWith(expectedPrefix)) return "Intro does not belong to you."

        musicFileService.getMusicFileById(introId) ?: return "Intro not found."
        musicFileService.deleteMusicFileById(introId)
        return null
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            URI(url).toURL()
            true
        } catch (e: Exception) {
            false
        }
    }
}

data class GuildInfo(
    val id: String,
    val name: String,
    val iconHash: String?
) {
    val iconUrl: String?
        get() = iconHash?.let { "https://cdn.discordapp.com/icons/$id/$it.png?size=64" }
}
