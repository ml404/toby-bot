package bot.toby.intro

import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.entities.Message.Attachment
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class IntroMediaLoaderTest {

    private val loader = IntroMediaLoader()

    private fun attachmentReturning(future: CompletableFuture<InputStream>): Attachment =
        mockk(relaxed = true) { every { proxy.download() } returns future }

    @Test
    fun `downloadAttachment returns the stream on success`() {
        val stream: InputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val future = mockk<CompletableFuture<InputStream>>()
        every { future.get(any(), any<TimeUnit>()) } returns stream

        assertSame(stream, loader.downloadAttachment(attachmentReturning(future)))
    }

    @Test
    fun `downloadAttachment returns null (no throw) when the bounded download fails`() {
        val future = mockk<CompletableFuture<InputStream>>()
        every { future.get(any(), any<TimeUnit>()) } throws RuntimeException("cdn timeout")

        assertNull(loader.downloadAttachment(attachmentReturning(future)))
    }

    @Test
    fun `readContents reads the stream to bytes`() {
        val bytes = byteArrayOf(4, 5, 6, 7)
        assertArrayEquals(bytes, loader.readContents(ByteArrayInputStream(bytes)))
    }
}
