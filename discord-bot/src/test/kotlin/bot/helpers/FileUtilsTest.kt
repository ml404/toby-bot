package bot.helpers

import bot.toby.helpers.FileUtils.readByteArrayToInputStream
import bot.toby.helpers.FileUtils.readInputStreamToByteArray
import bot.toby.helpers.FileUtils.streamsAreEqual
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.*

/**
 * FileUtils Tester.
 *
 * @author <Matthew Layton>
 * @since <pre>May 8, 2021</pre>
 * @version 1.0
</Matthew> */
class FileUtilsTest {
    @BeforeEach
    @Throws(Exception::class)
    fun before() {
    }

    @AfterEach
    @Throws(Exception::class)
    fun after() {
    }

    /**
     *
     * Method: readInputStreamToByteArray(InputStream inputStream)
     *
     */
    @Test
    @Throws(Exception::class)
    fun testReadInputStreamToByteArray() {
        val classLoader = javaClass.classLoader
        val mp3Resource1 = classLoader.getResource("test.mp3")
        val file = File(mp3Resource1?.file!!)


        val mp3Resource2 = classLoader.getResource("test.mp3")
        val fileInputStream: InputStream = FileInputStream(file)
        val bytes = readInputStreamToByteArray(fileInputStream)

        Assertions.assertNotNull(bytes)
        Assertions.assertArrayEquals(bytes, mp3Resource2?.openStream()?.readAllBytes())
    }

    /**
     *
     * Method: readByteArrayToInputStream(String fileContents)
     *
     */
    @Test
    @Throws(Exception::class)
    fun testReadByteArrayToInputStream() {
        val classLoader = javaClass.classLoader
        val mp3Resource = classLoader.getResource("test.mp3")
        val file = File(mp3Resource?.file!!)
        val inputStreamFromFile: InputStream = FileInputStream(file)
        val inputStreamFromString = readByteArrayToInputStream(mp3Resource.openStream().readAllBytes())

        Assertions.assertNotNull(inputStreamFromString)
        Assertions.assertTrue(streamsAreEqual(inputStreamFromFile, inputStreamFromString))
    }
}
