package helpers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import toby.helpers.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;
import static toby.helpers.FileUtils.streamsAreEqual;

/**
 * FileUtils Tester.
 *
 * @author <Matthew Layton>
 * @since <pre>May 8, 2021</pre>
 * @version 1.0
 */
public class FileUtilsTest {

    @BeforeEach
    public void before() throws Exception {
    }

    @AfterEach
    public void after() throws Exception {
    }

    /**
     *
     * Method: readInputStreamToByteArray(InputStream inputStream)
     *
     */
    @Test
    public void testReadInputStreamToByteArray() throws Exception {
        ClassLoader classLoader = getClass().getClassLoader();
        URL mp3Resource1 = classLoader.getResource("test.mp3");
        File file = new File(mp3Resource1.getFile());


        URL mp3Resource2 = classLoader.getResource("test.mp3");
        InputStream fileInputStream = new FileInputStream(file);
        byte[] bytes = FileUtils.readInputStreamToByteArray(fileInputStream);

        assertNotNull(bytes);
        assertArrayEquals(bytes, mp3Resource2.openStream().readAllBytes());

    }

    /**
     *
     * Method: readByteArrayToInputStream(String fileContents)
     *
     */
    @Test
    public void testReadByteArrayToInputStream() throws Exception {
        ClassLoader classLoader = getClass().getClassLoader();
        URL mp3Resource = classLoader.getResource("test.mp3");
        File file = new File(mp3Resource.getFile());
        InputStream inputStreamFromFile = new FileInputStream(file);
        InputStream inputStreamFromString = FileUtils.readByteArrayToInputStream(mp3Resource.openStream().readAllBytes());

        assertNotNull(inputStreamFromString);
        assertTrue(streamsAreEqual(inputStreamFromFile, inputStreamFromString));
    }


}
