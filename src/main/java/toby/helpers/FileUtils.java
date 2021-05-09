package toby.helpers;

import org.apache.commons.io.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
}
