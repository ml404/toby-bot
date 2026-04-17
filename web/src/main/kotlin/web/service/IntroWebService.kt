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
        const val MAX_INTRO_DURATION_SECONDS = 15
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

    fun getGuildMembers(guildId: Long): List<MemberInfo> {
        val guild = jda.getGuildById(guildId) ?: return emptyList()
        return guild.members
            .filter { !it.user.isBot }
            .map {
                MemberInfo(
                    id = it.id,
                    name = it.effectiveName,
                    avatarUrl = it.effectiveAvatarUrl
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun isSuperUser(discordId: Long, guildId: Long): Boolean {
        return userService.getUserById(discordId, guildId)?.superUser == true
    }

    fun getIntroCountsForGuilds(discordId: Long, guildIds: List<Long>): Map<Long, Int> {
        return guildIds.associateWith { gid ->
            userService.getUserById(discordId, gid)?.musicDtos?.size ?: 0
        }
    }

    fun getOrCreateUser(discordId: Long, guildId: Long): UserDto {
        return userService.getUserById(discordId, guildId)
            ?: UserDto(discordId = discordId, guildId = guildId).also { userService.createNewUser(it) }
    }

    fun getUserIntros(discordId: Long, guildId: Long): List<IntroViewModel> {
        val user = userService.getUserById(discordId, guildId) ?: return emptyList()
        return user.musicDtos.sortedBy { it.index }.map { dto ->
            val blobAsString = dto.musicBlob?.let { String(it) }.orEmpty()
            val url = blobAsString.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            val displayName = if (url != null && dto.fileName?.startsWith("http") == true) {
                // Legacy record: fileName was stored as the raw URL instead of a title.
                // Look up the video title and lazily migrate the record.
                val title = getYouTubeVideoTitle(url)
                if (title != null) {
                    dto.fileName = title
                    musicFileService.updateMusicFile(dto)
                }
                title ?: dto.fileName
            } else {
                dto.fileName
            }
            val thumbnailUrl = url?.let { extractVideoId(it) }?.let { "https://img.youtube.com/vi/$it/mqdefault.jpg" }
            IntroViewModel(dto.id.orEmpty(), dto.index, displayName, dto.introVolume, url, thumbnailUrl)
        }
    }

    fun setIntroByUrl(discordId: Long, guildId: Long, url: String, volume: Int, replaceIndex: Int?): String? {
        if (!isValidUrl(url)) return "Invalid URL provided."

        val user = getOrCreateUser(discordId, guildId)
        val existingIntros = user.musicDtos.sortedBy { it.index }

        if (replaceIndex == null && existingIntros.size >= MAX_INTRO_COUNT) {
            return "You already have $MAX_INTRO_COUNT intros. Please select one to replace."
        }

        val preview = fetchYouTubePreview(url)
        if (preview?.durationSeconds != null && preview.durationSeconds > MAX_INTRO_DURATION_SECONDS) {
            return "Video is too long (${preview.durationSeconds}s). Max allowed is ${MAX_INTRO_DURATION_SECONDS}s."
        }

        val displayName = preview?.title ?: url
        val urlBytes = url.toByteArray()
        val selectedDto = replaceIndex?.let { idx -> existingIntros.find { it.index == idx } }

        if (selectedDto != null) {
            selectedDto.fileName = displayName
            selectedDto.musicBlob = urlBytes
            selectedDto.musicBlobHash = MusicDto.computeHash(urlBytes)
            selectedDto.introVolume = volume
            musicFileService.updateMusicFile(selectedDto)
        } else {
            val newIndex = existingIntros.size + 1
            val newDto = MusicDto(user, newIndex, displayName, volume, urlBytes)
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

    fun updateIntroVolume(discordId: Long, guildId: Long, introId: String, volume: Int): String? {
        val expectedPrefix = "${guildId}_${discordId}_"
        if (!introId.startsWith(expectedPrefix)) return "Intro does not belong to you."

        val dto = musicFileService.getMusicFileById(introId) ?: return "Intro not found."
        dto.introVolume = volume.coerceIn(1, 100)
        musicFileService.updateMusicFile(dto)
        return null
    }

    fun updateIntroName(discordId: Long, guildId: Long, introId: String, name: String): String? {
        val expectedPrefix = "${guildId}_${discordId}_"
        if (!introId.startsWith(expectedPrefix)) return "Intro does not belong to you."
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "Name cannot be empty."
        if (trimmed.length > 200) return "Name is too long (max 200 characters)."

        val dto = musicFileService.getMusicFileById(introId) ?: return "Intro not found."
        dto.fileName = trimmed
        musicFileService.updateMusicFile(dto)
        return null
    }

    /**
     * Reassign the `index` field across the caller's intros to match the supplied id ordering.
     * The rest of the bot selects randomly between intros so order is purely organisational.
     */
    fun reorderIntros(discordId: Long, guildId: Long, orderedIds: List<String>): String? {
        val expectedPrefix = "${guildId}_${discordId}_"
        if (orderedIds.any { !it.startsWith(expectedPrefix) }) return "One or more intros do not belong to you."

        val user = userService.getUserById(discordId, guildId) ?: return "User not found."
        val currentIds = user.musicDtos.map { it.id }.toSet()
        if (orderedIds.toSet() != currentIds) return "Reorder list does not match your intros."

        // Two-phase rename: stash as negative temp indexes first to avoid unique-key collisions,
        // then assign the final 1..N indexes.
        orderedIds.forEachIndexed { idx, id ->
            musicFileService.getMusicFileById(id)?.let { dto ->
                dto.index = -(idx + 1)
                musicFileService.updateMusicFile(dto)
            }
        }
        orderedIds.forEachIndexed { idx, id ->
            musicFileService.getMusicFileById(id)?.let { dto ->
                dto.index = idx + 1
                musicFileService.updateMusicFile(dto)
            }
        }
        return null
    }

    /** Public preview used by the web form to pre-flight a URL. */
    fun fetchYouTubePreview(url: String): YouTubePreview? {
        val videoId = extractVideoId(url) ?: return null
        val apiKey = System.getenv("YOUTUBE_API_KEY")
            ?: return YouTubePreview(
                videoId = videoId,
                title = null,
                thumbnailUrl = "https://img.youtube.com/vi/$videoId/mqdefault.jpg",
                durationSeconds = null
            )
        return try {
            val apiUrl = "https://www.googleapis.com/youtube/v3/videos" +
                "?id=$videoId&part=snippet,contentDetails&key=$apiKey"
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            if (connection.responseCode != 200) {
                return YouTubePreview(
                    videoId = videoId,
                    title = null,
                    thumbnailUrl = "https://img.youtube.com/vi/$videoId/mqdefault.jpg",
                    durationSeconds = null
                )
            }
            val body = connection.inputStream.bufferedReader().readText()
            val item = objectMapper.readTree(body).path("items").firstOrNull() ?: return null
            val snippet = item.path("snippet")
            val title = snippet.path("title").asText().takeIf { it.isNotBlank() }
            val thumb = snippet.path("thumbnails").let {
                it.path("medium").path("url").asText().takeIf { s -> s.isNotBlank() }
                    ?: it.path("default").path("url").asText().takeIf { s -> s.isNotBlank() }
            } ?: "https://img.youtube.com/vi/$videoId/mqdefault.jpg"
            val duration = parseIsoDurationSeconds(item.path("contentDetails").path("duration").asText())
            YouTubePreview(videoId, title, thumb, duration)
        } catch (_: Exception) {
            YouTubePreview(
                videoId = videoId,
                title = null,
                thumbnailUrl = "https://img.youtube.com/vi/$videoId/mqdefault.jpg",
                durationSeconds = null
            )
        }
    }

    /**
     * Back-compat helper used by legacy tests. Returns the YouTube video title or null.
     * New code should prefer [fetchYouTubePreview] which also returns thumbnail + duration.
     */
    fun getYouTubeVideoTitle(url: String): String? = fetchYouTubePreview(url)?.title

    private fun extractVideoId(url: String): String? {
        val regex = Regex("(?<=v=|/videos/|embed/|youtu\\.be/|/v/|/e/|watch\\?v=|&v=|^youtu\\.be/)([^#&?\\n]+)")
        return regex.find(url)?.value
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            URI(url).toURL()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Parses ISO-8601 durations like "PT1M3S" or "PT15S" into seconds. Returns null if unparseable. */
    private fun parseIsoDurationSeconds(iso: String?): Int? {
        if (iso.isNullOrBlank()) return null
        val regex = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?")
        val match = regex.matchEntire(iso) ?: return null
        val (h, m, s) = match.destructured
        return h.toIntOrNull().orZero() * 3600 + m.toIntOrNull().orZero() * 60 + s.toIntOrNull().orZero()
    }

    private fun Int?.orZero(): Int = this ?: 0
}

data class IntroViewModel(
    val id: String,
    val index: Int?,
    val fileName: String?,
    val introVolume: Int?,
    val url: String?,
    val thumbnailUrl: String? = null
)

data class GuildInfo(
    val id: String,
    val name: String,
    val iconHash: String?
) {
    val iconUrl: String?
        get() = iconHash?.let { "https://cdn.discordapp.com/icons/$id/$it.png?size=64" }
}

data class MemberInfo(
    val id: String,
    val name: String,
    val avatarUrl: String?
)

data class YouTubePreview(
    val videoId: String,
    val title: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Int?
)
