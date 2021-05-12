package toby.helpers;

import org.apache.commons.io.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

public class FileUtils {

    public static byte[] readInputStreamToByteArray(InputStream inputStream) throws ExecutionException, InterruptedException, IOException {
        return inputStream.readAllBytes();
    }

    public static InputStream readByteArrayToInputStream(byte[] fileContents) {
        return new ByteArrayInputStream(fileContents);
    }

    public static boolean streamsAreEqual(InputStream inputStream1, InputStream inputStream2) {
        try {
            return IOUtils.contentEquals(inputStream1, inputStream2);
        } catch (IOException ignored) {
        }
        return false;
    }


    public static InputStream readByteArrayToUTF8InputStream(byte[] fileContents) {
        return new ByteArrayInputStream(Arrays.toString(fileContents).getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] readInputStreamToUTF8ByteArray(InputStream inputStream) throws IOException {
        return Arrays.toString(inputStream.readAllBytes()).getBytes(StandardCharsets.UTF_8);
    }
}
