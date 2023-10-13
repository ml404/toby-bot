package toby.helpers;

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

    public static boolean streamsAreEqual(InputStream input1, InputStream input2) throws IOException {
        boolean error = false;
        try {
            byte[] buffer1 = new byte[1024];
            byte[] buffer2 = new byte[1024];
            try {
                int numRead1;
                int numRead2;
                while (true) {
                    numRead1 = input1.read(buffer1);
                    numRead2 = input2.read(buffer2);
                    if (numRead1 > -1) {
                        if (numRead2 != numRead1) return false;
                        // Otherwise same number of bytes read
                        if (!Arrays.equals(buffer1, buffer2)) return false;
                        // Otherwise same bytes read, so continue ...
                    } else {
                        // Nothing more in stream 1 ...
                        return numRead2 < 0;
                    }
                }
            } finally {
                input1.close();
            }
        } catch (IOException | RuntimeException e) {
            error = true; // this error should be thrown, even if there is an error closing stream 2
            throw e;
        } finally {
            try {
                input2.close();
            } catch (IOException e) {
                if (!error) throw e;
            }
        }
    }

    public static InputStream readByteArrayToUTF8InputStream(byte[] fileContents) {
        return new ByteArrayInputStream(Arrays.toString(fileContents).getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] readInputStreamToUTF8ByteArray(InputStream inputStream) throws IOException {
        return Arrays.toString(inputStream.readAllBytes()).getBytes(StandardCharsets.UTF_8);
    }
}
