package bot.toby.intro

import bot.toby.helpers.FileUtils
import common.logging.DiscordLogger
import net.dv8tion.jda.api.entities.Message.Attachment
import org.springframework.stereotype.Service
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * I/O for intro media: pulls bytes off a Discord attachment proxy and
 * reads an InputStream into a byte array. Lifted out of IntroHelper so
 * the persistence path doesn't have to know about Discord download
 * mechanics, and so the notification flow can share the same loader.
 */
@Service
class IntroMediaLoader {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    fun downloadAttachment(attachment: Attachment): InputStream? =
        // Bound the blocking download: an unresponsive Discord CDN must not
        // hang the calling handler forever. Failures are logged (not dumped
        // to stderr) so production can actually diagnose them.
        runCatching { attachment.proxy.download().get(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            .onFailure { logger.error { "Failed to download intro attachment '${attachment.fileName}': ${it.message}" } }
            .getOrNull()

    fun readContents(inputStream: InputStream): ByteArray? =
        runCatching { FileUtils.readInputStreamToByteArray(inputStream) }
            .onFailure { logger.error { "Failed to read intro media stream: ${it.message}" } }
            .getOrNull()

    private companion object {
        const val DOWNLOAD_TIMEOUT_SECONDS = 30L
    }
}
