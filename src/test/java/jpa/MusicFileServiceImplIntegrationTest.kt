package jpa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import toby.Application;
import toby.jpa.dto.MusicDto;
import toby.jpa.service.IMusicFileService;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = Application.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
public class MusicFileServiceImplIntegrationTest {

    @Autowired
    private IMusicFileService musicFileService;


    @BeforeEach
    public void setUp() {
    }

    @AfterEach
    public void tearDown() {
    }

    @Test
    public void whenValidDiscordIdAndGuild_thenUserShouldBeFound() {
        MusicDto musicDto1 = new MusicDto();
        musicDto1.setId("1_1");
        musicDto1.setFileName("filename");
        musicDto1.setMusicBlob("Some data".getBytes());
        musicFileService.createNewMusicFile(musicDto1);
        MusicDto dbMusicDto1 = musicFileService.getMusicFileById(musicDto1.getId());

        assertEquals(dbMusicDto1.getId(), musicDto1.getId());
        assertEquals(dbMusicDto1.getFileName(), musicDto1.getFileName());
        assertArrayEquals(dbMusicDto1.getMusicBlob(), musicDto1.getMusicBlob());

    }

    @Test
    public void testUpdate_thenNewUserValuesShouldBeReturned() {
        MusicDto musicDto1 = new MusicDto();
        musicDto1.setId("1_1");
        musicDto1.setFileName("file 1");
        musicDto1.setMusicBlob("some data 1".getBytes());
        musicDto1 = musicFileService.createNewMusicFile(musicDto1);
        MusicDto dbMusicDto1 = musicFileService.getMusicFileById(musicDto1.getId());

        assertEquals(dbMusicDto1.getId(), musicDto1.getId());
        assertEquals(dbMusicDto1.getFileName(), musicDto1.getFileName());
        assertArrayEquals(dbMusicDto1.getMusicBlob(), musicDto1.getMusicBlob());


        MusicDto musicDto2 = new MusicDto();
        musicDto2.setId("1_1");
        musicDto2.setFileName("file 2");
        musicDto2.setMusicBlob("some data 2".getBytes());
        musicDto2 = musicFileService.updateMusicFile(musicDto2);
        MusicDto dbMusicDto2 = musicFileService.getMusicFileById(musicDto2.getId());

        assertEquals(dbMusicDto2.getId(), musicDto2.getId());
        assertEquals(dbMusicDto2.getFileName(), musicDto2.getFileName());
        assertArrayEquals(dbMusicDto2.getMusicBlob(), musicDto2.getMusicBlob());


    }

    //@Test
    //Basically h2 uses BLOB type for binaries, which means I need to change the musicDTO mapping specifically for test which would break the PROD mapping I have.
    //So this test exists but is kinda useless
    public void musicDtoBlobSerializesAndDeserializesCorrectly() throws IOException, URISyntaxException {
        ClassLoader classLoader = getClass().getClassLoader();
        URL mp3Resource = classLoader.getResource("test.mp3");
        byte[] musicData = Files.readAllBytes(Paths.get(mp3Resource.toURI()));

        MusicDto musicDto = new MusicDto();
        musicDto.setMusicBlob(musicData);

        musicDto.setId("1_1");
        musicDto.setFileName("filename");

        musicFileService.createNewMusicFile(musicDto);
        MusicDto dbMusicDto = musicFileService.getMusicFileById(musicDto.getId());

        assertEquals(dbMusicDto.getId(), musicDto.getId());
        assertEquals(dbMusicDto.getFileName(), musicDto.getFileName());
        assertArrayEquals(musicDto.getMusicBlob(), dbMusicDto.getMusicBlob());
    }

}
