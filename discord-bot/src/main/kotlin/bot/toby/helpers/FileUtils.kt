package bot.toby.helpers

import java.io.ByteArrayInputStream
import java.io.InputStream

object FileUtils {
    @JvmStatic
    fun readInputStreamToByteArray(inputStream: InputStream?): ByteArray? {
        return inputStream?.readAllBytes()
    }

    @JvmStatic
    fun readByteArrayToInputStream(fileContents: ByteArray?): InputStream {
        return ByteArrayInputStream(fileContents)
    }

    @JvmStatic
    fun streamsAreEqual(input1: InputStream, input2: InputStream): Boolean {
        val buffer1 = ByteArray(1024)
        val buffer2 = ByteArray(1024)

        var numRead1: Int
        var numRead2: Int
        while (true) {
            numRead1 = input1.read(buffer1)
            numRead2 = input2.read(buffer2)
            if (numRead1 > -1) {
                if (numRead2 != numRead1) return false
                // Otherwise same number of bytes read
                if (!buffer1.contentEquals(buffer2)) return false
                // Otherwise same bytes read, so continue ...
            } else {
                // Nothing more in stream 1 ...
                return numRead2 < 0
            }
        }
    }
}
