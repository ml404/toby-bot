package web.service

import com.fasterxml.jackson.databind.ObjectMapper
import common.logging.DiscordLogger
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
        const val MAX_CLIP_DURATION_MS = MAX_INTRO_DURATION_SECONDS * 1000
        private val objectMapper = ObjectMapper()
        private val logger = DiscordLogger(IntroWebService::class.java)
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
            val blobBytes = dto.musicBlob
            val blobAsString = blobBytes?.let { String(it) }.orEmpty()
            val blobIsUrl = blobAsString.startsWith("http://") || blobAsString.startsWith("https://")
            val blobIsMissing = blobBytes == null || blobBytes.isEmpty()
            // Salvage path for a corrupt state seen in production: a row
            // saved via the web form ended up with `musicBlob = NULL` while
            // `fileName` held the source URL. When the blob is truly absent
            // (null/empty) but the fileName parses as a URL, treat that URL
            // as the source — the write below heals the blob on the way out.
            // Real file uploads (non-empty blob that doesn't decode to a URL)
            // are left strictly alone; we never replace an MP3 with a URL
            // derived from a URL-shaped fileName.
            val fileNameAsUrl = dto.fileName
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            val rawUrl = when {
                blobIsUrl -> blobAsString
                blobIsMissing -> fileNameAsUrl
                else -> null
            }
            val url = rawUrl?.let { canonicaliseShortsUrl(it) }
            var dirty = false
            // Whenever we can resolve a URL for this row, make sure the stored
            // blob matches its canonical form. Covers three cases in one
            // branch: (1) Shorts -> watch?v= canonicalisation, (2) fileName
            // -> blob rescue, (3) blob bytes drifted from canonical for any
            // other reason.
            if (url != null) {
                val newBytes = url.toByteArray()
                val blobAlreadyMatches = blobBytes?.contentEquals(newBytes) == true
                if (!blobAlreadyMatches) {
                    dto.musicBlob = newBytes
                    dto.musicBlobHash = MusicDto.computeHash(newBytes)
                    dirty = true
                    val reason = when {
                        blobIsMissing -> "musicBlob was NULL/empty; rescued URL from fileName ('${dto.fileName}')"
                        rawUrl != url -> "canonicalising Shorts URL '$rawUrl' -> '$url'"
                        else -> "stored blob drifted from canonical; rewriting to '$url'"
                    }
                    logger.info { "Healing intro ${dto.id}: $reason" }
                }
            } else if (blobIsMissing) {
                logger.warn { "Intro ${dto.id} has empty musicBlob and fileName '${dto.fileName}' is not a URL; cannot heal" }
            }
            val displayName = if (url != null && dto.fileName?.startsWith("http") == true) {
                // Legacy record: fileName was stored as the raw URL instead of a title.
                // Look up the video title and lazily migrate the record.
                val title = getYouTubeVideoTitle(url)
                if (title != null) {
                    dto.fileName = title
                    dirty = true
                    logger.info { "Replacing URL-shaped fileName on intro ${dto.id} with title '$title'" }
                } else {
                    logger.info { "No YouTube title available for intro ${dto.id} (url=$url); leaving fileName as URL" }
                }
                title ?: dto.fileName
            } else {
                dto.fileName
            }
            if (dirty) {
                runCatching { musicFileService.updateMusicFile(dto) }
                    .onFailure { e -> logger.error { "Failed to persist lazy-migrated intro ${dto.id}: ${e.message}" } }
            }
            val videoId = url?.let { extractVideoId(it) }
            val thumbnailUrl = videoId?.let { "https://img.youtube.com/vi/$it/mqdefault.jpg" }
            IntroViewModel(
                id = dto.id.orEmpty(),
                index = dto.index,
                fileName = displayName,
                introVolume = dto.introVolume,
                url = url,
                thumbnailUrl = thumbnailUrl,
                videoId = videoId,
                startMs = dto.startMs,
                endMs = dto.endMs
            )
        }
    }

    fun setIntroByUrl(
        discordId: Long,
        guildId: Long,
        url: String,
        volume: Int,
        replaceIndex: Int?,
        startMs: Int?,
        endMs: Int?
    ): String? {
        if (!isValidUrl(url)) return "Invalid URL provided."

        val user = getOrCreateUser(discordId, guildId)
        val existingIntros = user.musicDtos.sortedBy { it.index }

        if (replaceIndex == null && existingIntros.size >= MAX_INTRO_COUNT) {
            return "You already have $MAX_INTRO_COUNT intros. Please select one to replace."
        }

        // Normalise Shorts URLs to the canonical watch form before we do
        // anything with them — YouTube previews and the saved row both embed
        // more reliably from `watch?v=<id>` than from `/shorts/<id>`.
        val normalisedUrl = canonicaliseShortsUrl(url)
        val preview = fetchYouTubePreview(normalisedUrl)
        val sourceDurationMs = preview?.durationSeconds?.let { it * 1000 }

        validateClip(startMs, endMs, sourceDurationMs)?.let { return it }

        val displayName = preview?.title ?: normalisedUrl
        val urlBytes = normalisedUrl.toByteArray()
        val selectedDto = replaceIndex?.let { idx -> existingIntros.find { it.index == idx } }

        if (selectedDto != null) {
            selectedDto.fileName = displayName
            selectedDto.musicBlob = urlBytes
            selectedDto.musicBlobHash = MusicDto.computeHash(urlBytes)
            selectedDto.introVolume = volume
            selectedDto.startMs = startMs
            selectedDto.endMs = endMs
            musicFileService.updateMusicFile(selectedDto)
        } else {
            // Pre-empt the hash dedupe in createNewMusicFile so we can tell
            // the user *which* slot already holds this URL. Normalise both
            // sides through canonicaliseShortsUrl so a pre-migration
            // `/shorts/ID` row and a fresh `watch?v=ID` save still collide.
            val duplicateSlot = existingIntros.firstOrNull { existing ->
                val existingUrl = existing.musicBlob?.let { String(it) }
                    ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    ?.let(::canonicaliseShortsUrl)
                existingUrl == normalisedUrl
            }
            if (duplicateSlot != null) {
                return "You already have this intro in slot ${duplicateSlot.index}. Pick that slot to replace it."
            }
            val newIndex = existingIntros.size + 1
            val newDto = MusicDto(user, newIndex, displayName, volume, urlBytes, startMs, endMs)
            // Safety net for any collision the byte compare misses — the
            // hash check in createNewMusicFile still fires.
            musicFileService.createNewMusicFile(newDto)
                ?: return "You already have this intro in another slot. Pick that slot to replace it."
        }

        return null
    }

    fun setIntroByFile(
        discordId: Long,
        guildId: Long,
        file: MultipartFile,
        volume: Int,
        replaceIndex: Int?,
        startMs: Int?,
        endMs: Int?
    ): String? {
        if (file.isEmpty) return "No file provided."
        if (!file.originalFilename.orEmpty().endsWith(".mp3", ignoreCase = true)) {
            return "Only MP3 files are supported."
        }
        if (file.size > MAX_FILE_SIZE) {
            return "File exceeds maximum size of ${MAX_FILE_SIZE / 1024}KB."
        }

        // Source duration is unknown for uploaded files here — let the client-side
        // preview enforce bounds against the real audio. Clip-length rule still applies.
        validateClip(startMs, endMs, sourceDurationMs = null)?.let { return it }

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
            selectedDto.startMs = startMs
            selectedDto.endMs = endMs
            musicFileService.updateMusicFile(selectedDto)
        } else {
            val newIndex = existingIntros.size + 1
            val newDto = MusicDto(user, newIndex, fileName, volume, fileBytes, startMs, endMs)
            musicFileService.createNewMusicFile(newDto)
                ?: return "This file already exists as one of your intros."
        }

        return null
    }

    /**
     * Validates a clip range against the source duration.
     *
     * - Both null: no clipping. Only reject when source is known and longer than the 15s cap.
     * - start/end present: start must be < end; clip span (end - start) must be <= MAX_CLIP_DURATION_MS.
     * - When [sourceDurationMs] is known, end must be <= sourceDurationMs.
     *
     * Returns an error message or null if the clip is valid.
     */
    fun validateClip(startMs: Int?, endMs: Int?, sourceDurationMs: Int?): String? {
        if (startMs == null && endMs == null) {
            if (sourceDurationMs != null && sourceDurationMs > MAX_CLIP_DURATION_MS) {
                val seconds = sourceDurationMs / 1000
                return "Video is too long (${seconds}s). Max allowed is ${MAX_INTRO_DURATION_SECONDS}s — set a start/end clip to use a longer source."
            }
            return null
        }
        val start = startMs ?: 0
        if (start < 0) return "Start time cannot be negative."
        if (endMs != null) {
            if (endMs <= start) return "End time must be greater than start time."
            if (sourceDurationMs != null && endMs > sourceDurationMs) {
                return "End time exceeds the source duration."
            }
            if (endMs - start > MAX_CLIP_DURATION_MS) {
                return "Clip is too long (${(endMs - start) / 1000}s). Max allowed is ${MAX_INTRO_DURATION_SECONDS}s."
            }
        } else if (sourceDurationMs != null) {
            // Only start was provided; the clip runs from start to end of source.
            if (sourceDurationMs - start > MAX_CLIP_DURATION_MS) {
                return "Clip is too long (${(sourceDurationMs - start) / 1000}s). Max allowed is ${MAX_INTRO_DURATION_SECONDS}s."
            }
        }
        return null
    }

    private fun requireOwnedIntro(discordId: Long, guildId: Long, introId: String): String? =
        if (introId.startsWith("${guildId}_${discordId}_")) null
        else "Intro does not belong to you."

    fun deleteIntro(discordId: Long, guildId: Long, introId: String): String? {
        requireOwnedIntro(discordId, guildId, introId)?.let { return it }

        musicFileService.getMusicFileById(introId) ?: return "Intro not found."
        musicFileService.deleteMusicFileById(introId)
        return null
    }

    fun updateIntroVolume(discordId: Long, guildId: Long, introId: String, volume: Int): String? {
        requireOwnedIntro(discordId, guildId, introId)?.let { return it }

        val dto = musicFileService.getMusicFileById(introId) ?: return "Intro not found."
        dto.introVolume = volume.coerceIn(1, 100)
        musicFileService.updateMusicFile(dto)
        return null
    }

    fun updateIntroTimestamps(
        discordId: Long,
        guildId: Long,
        introId: String,
        startMs: Int?,
        endMs: Int?
    ): String? {
        requireOwnedIntro(discordId, guildId, introId)?.let { return it }

        val dto = musicFileService.getMusicFileById(introId) ?: return "Intro not found."
        val sourceDurationMs = run {
            val blobString = dto.musicBlob?.let { String(it) }.orEmpty()
            if (blobString.startsWith("http://") || blobString.startsWith("https://")) {
                fetchYouTubePreview(blobString)?.durationSeconds?.let { it * 1000 }
            } else null
        }
        validateClip(startMs, endMs, sourceDurationMs)?.let { return it }

        dto.startMs = startMs
        dto.endMs = endMs
        musicFileService.updateMusicFile(dto)
        return null
    }

    fun updateIntroName(discordId: Long, guildId: Long, introId: String, name: String): String? {
        requireOwnedIntro(discordId, guildId, introId)?.let { return it }
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
        // Exclude `/` from the capture so a trailing slash (e.g. `…/shorts/ID/`)
        // doesn't get baked into the videoId and break the iframe embed URL.
        val regex = Regex("(?<=v=|/videos/|embed/|youtu\\.be/|/v/|/e/|watch\\?v=|&v=|^youtu\\.be/|/shorts/)([^#&?/\\n]+)")
        return regex.find(url)?.value
    }

    /**
     * Rewrite a `youtube.com/shorts/<id>` URL to its canonical `watch?v=<id>`
     * form. Non-shorts URLs are returned unchanged. Built from the extracted
     * video id rather than a string-level replace so query suffixes (`?si=…`)
     * don't collide with the inserted `?v=`.
     */
    internal fun canonicaliseShortsUrl(url: String): String {
        if (!url.contains("/shorts/", ignoreCase = true)) return url
        val videoId = extractVideoId(url) ?: return url
        return "https://www.youtube.com/watch?v=$videoId"
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
    val thumbnailUrl: String? = null,
    val videoId: String? = null,
    val startMs: Int? = null,
    val endMs: Int? = null
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
