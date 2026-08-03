package bot.toby.intro

import bot.toby.helpers.HttpHelper
import bot.toby.helpers.URLHelper
import common.intro.IntroClip
import common.logging.DiscordLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.dv8tion.jda.api.entities.Message.Attachment
import org.springframework.stereotype.Service
import kotlin.time.Duration.Companion.seconds

@Service
class IntroValidationService(
    private val httpHelper: HttpHelper,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + dispatcher)

    fun isValidAttachment(attachment: Attachment): Boolean =
        attachment.fileExtension == "mp3" && attachment.size <= MAX_FILE_SIZE

    fun isValidUrl(url: String): Boolean = URLHelper.isValidURL(url)

    /**
     * Pulls the optional volume out of a DM reply like
     * `https://www.youtube.com/watch?v=ID 90`.
     *
     * Only a whitespace-separated token that is *entirely* digits counts.
     * The previous `\b(\d{1,3})\b` scan matched digits inside the URL itself,
     * so `https://youtu.be/ID?t=42` silently set the intro to 42% and
     * `...&list=PL9&index=7` set it to 7% — neither of which the user asked
     * for. Either order works (`URL 90` or `90 URL`), since the token has to
     * stand alone to match at all.
     */
    fun parseVolume(content: String): Int? = content
        .split(WHITESPACE)
        .firstOrNull { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) }
        ?.toIntOrNull()
        ?.takeIf { it in 0..100 }

    fun convertShortsUrls(url: String): String = url.replace("/shorts/", "/watch?v=")

    suspend fun checkForOverlyLongIntroDuration(url: String): Boolean {
        val duration = withContext(dispatcher) { httpHelper.getYouTubeVideoDuration(url) }
        if (duration != null) {
            logger.info { "Duration of intro is '$duration' vs limit of '$INTRO_LIMIT'" }
            return duration > INTRO_LIMIT
        }
        return false
    }

    /**
     * Source duration in milliseconds, or null when it can't be determined
     * (non-YouTube URL, API key missing, lookup failure). Callers feed this
     * into [IntroClip.validate]; a null simply relaxes the "end must be
     * within the source" rule, exactly as the web form does for uploads.
     *
     * Note this fails *open* where [validateIntroLength] fails closed: a
     * YouTube API blip lets an intro through rather than rejecting every
     * intro until the API recovers. That matches the web form, which has
     * always treated an unavailable preview as "duration unknown".
     */
    suspend fun sourceDurationMs(url: String): Int? = runCatching {
        withContext(dispatcher) { httpHelper.getYouTubeVideoDuration(url) }?.inWholeMilliseconds?.toInt()
    }.onFailure {
        logger.warn { "Could not determine source duration for '$url': ${it::class.simpleName}: ${it.message}" }
    }.getOrNull()

    /**
     * Clip-aware replacement for [validateIntroLength]. Looks the source
     * duration up once, then defers to the shared [IntroClip] rules so a
     * long video can be accepted when the user clips it — the behaviour the
     * web form has always had and the slash command never did.
     *
     * @return an error message to show the user, or null when the intro is fine.
     */
    fun validateClipAgainstSource(url: String?, startMs: Int?, endMs: Int?, onResult: (String?) -> Unit) {
        scope.launch {
            val sourceDurationMs = url?.takeIf { it.isNotBlank() }?.let { sourceDurationMs(it) }
            onResult(IntroClip.validate(startMs, endMs, sourceDurationMs))
        }
    }

    /**
     * Async wrapper around [checkForOverlyLongIntroDuration]. The async path
     * conservatively reports "over limit" on failure so an error can't sneak
     * a too-long intro past the check.
     */
    fun validateIntroLength(url: String, onResult: (Boolean) -> Unit) {
        logger.info { "Checking duration of '$url'" }
        scope.launch {
            try {
                onResult(checkForOverlyLongIntroDuration(url))
            } catch (e: Exception) {
                logger.error { "Error checking intro length for '$url': ${e::class.simpleName}: ${e.message}" }
                onResult(true)
            }
        }
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        const val MAX_FILE_SIZE = 550 * 1024
        const val MAX_FILE_SIZE_KB = "${MAX_FILE_SIZE / 1024}"

        // Shared with the web form via IntroClip so the two surfaces can't
        // disagree on how long an intro is allowed to play for.
        val INTRO_LIMIT = IntroClip.MAX_DURATION_SECONDS.seconds
    }
}
