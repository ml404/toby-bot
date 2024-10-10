package toby.helpers

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ExecutionException

object FileUtils {
    @JvmStatic
    @Throws(ExecutionException::class, InterruptedException::class, IOException::class)
    fun readInputStreamToByteArray(inputStream: InputStream?): ByteArray? {
        return inputStream?.readAllBytes()
    }

    @JvmStatic
    fun readByteArrayToInputStream(fileContents: ByteArray?): InputStream {
        return ByteArrayInputStream(fileContents)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun streamsAreEqual(input1: InputStream, input2: InputStream): Boolean {
        var error = false
        try {
            val buffer1 = ByteArray(1024)
            val buffer2 = ByteArray(1024)
            try {
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
            } finally {
                input1.close()
            }
        } catch (e: IOException) {
            error = true // this error should be thrown, even if there is an error closing stream 2
            throw e
        } catch (e: RuntimeException) {
            error = true
            throw e
        } finally {
            try {
                input2.close()
            } catch (e: IOException) {
                if (!error) throw e
            }
        }
    }

    fun readByteArrayToUTF8InputStream(fileContents: ByteArray): InputStream {
        return ByteArrayInputStream(fileContents.contentToString().toByteArray(StandardCharsets.UTF_8))
    }

    @Throws(IOException::class)
    fun readInputStreamToUTF8ByteArray(inputStream: InputStream): ByteArray {
        return inputStream.readAllBytes().contentToString().toByteArray(StandardCharsets.UTF_8)
    }

    fun computeHash(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hashBytes = md.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
