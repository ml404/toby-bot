package bot.toby.intro

import bot.toby.helpers.HttpHelper
import bot.toby.helpers.URLHelper
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

    fun parseVolume(content: String): Int? {
        val volumeRegex = """\b(\d{1,3})\b""".toRegex()
        return volumeRegex.find(content)?.value?.toIntOrNull()?.takeIf { it in 0..100 }
    }

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
     * Async wrapper around [checkForOverlyLongIntroDuration]. The async path
     * conservatively reports "over limit" on failure so an error can't sneak
     * a too-long intro past the check.
     */
    fun validateIntroLength(url: String, onResult: (Boolean) -> Unit) {
        logger.info { "Checking duration of '$url'" }
        scope.launch {
            try {
                onResult(checkForOverlyLongIntroDuration(url))
            } catch (_: Exception) {
                logger.error { "Error checking intro length for '$url'" }
                onResult(true)
            }
        }
    }

    companion object {
        const val MAX_FILE_SIZE = 550 * 1024
        const val MAX_FILE_SIZE_KB = "${MAX_FILE_SIZE / 1024}"
        val INTRO_LIMIT = 15.seconds
    }
}
