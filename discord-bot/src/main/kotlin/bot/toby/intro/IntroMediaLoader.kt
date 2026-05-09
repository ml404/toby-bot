package bot.toby.intro

import bot.toby.helpers.FileUtils
import net.dv8tion.jda.api.entities.Message.Attachment
import org.springframework.stereotype.Service
import java.io.InputStream

/**
 * I/O for intro media: pulls bytes off a Discord attachment proxy and
 * reads an InputStream into a byte array. Lifted out of IntroHelper so
 * the persistence path doesn't have to know about Discord download
 * mechanics, and so the notification flow can share the same loader.
 */
@Service
class IntroMediaLoader {

    fun downloadAttachment(attachment: Attachment): InputStream? =
        runCatching { attachment.proxy.download().get() }
            .onFailure { it.printStackTrace() }
            .getOrNull()

    fun readContents(inputStream: InputStream): ByteArray? =
        runCatching { FileUtils.readInputStreamToByteArray(inputStream) }.getOrNull()
}
