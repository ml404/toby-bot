package jpa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import toby.Application;
import toby.jpa.dto.MusicDto;
import toby.jpa.persistence.IMusicFilePersistence;
import toby.jpa.persistence.IUserPersistence;
import toby.jpa.service.IMusicFileService;
import toby.jpa.service.IUserService;

import java.io.IOException;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = Application.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
public class MusicFileServiceImplIntegrationTest {

    @Autowired
    private IMusicFileService musicFileService;

    @Autowired
    private IMusicFilePersistence musicPersistence;


    @Autowired
    private IUserService userService;

    @Autowired
    private IUserPersistence userPersistence;



    @BeforeEach
    public void setUp() {
    }

    @AfterEach
    public void tearDown(){
    }

    @Test
    public void whenValidDiscordIdAndGuild_thenUserShouldBeFound() {
        MusicDto musicDto1 = new MusicDto();
        musicDto1.setId("1_1");
        musicDto1.setFileName("filename");
        musicDto1.setMusicBlob("Some data".getBytes());
        musicFileService.createNewMusicFile(musicDto1);
        MusicDto dbMusicDto1 = musicFileService.getMusicFileById(musicDto1.getId());

        assertEquals(dbMusicDto1.getId(),musicDto1.getId());
        assertEquals(dbMusicDto1.getFileName(),musicDto1.getFileName());
        assertEquals(dbMusicDto1.getMusicBlob(),musicDto1.getMusicBlob());

    }

    @Test
    public void testUpdate_thenNewUserValuesShouldBeReturned() {
        MusicDto musicDto1 = new MusicDto();
        musicDto1.setId("1_1");
        musicDto1.setFileName("file 1");
        musicDto1.setMusicBlob("some data 1".getBytes());
        musicDto1 = musicFileService.createNewMusicFile(musicDto1);
        MusicDto dbMusicDto1 = musicFileService.getMusicFileById(musicDto1.getId());

        assertEquals(dbMusicDto1.getId(),musicDto1.getId());
        assertEquals(dbMusicDto1.getFileName(),musicDto1.getFileName());
        assertEquals(dbMusicDto1.getMusicBlob(),musicDto1.getMusicBlob());


        MusicDto musicDto2 = new MusicDto();
        musicDto2.setId("1_1");
        musicDto2.setFileName("file 2");
        musicDto2.setMusicBlob("some data 2".getBytes());
        musicDto2 = musicFileService.updateMusicFile(musicDto2);
        MusicDto dbMusicDto2 = musicFileService.getMusicFileById(musicDto2.getId());

        assertEquals(dbMusicDto2.getId(),musicDto2.getId());
        assertEquals(dbMusicDto2.getFileName(),musicDto2.getFileName());
        assertEquals(dbMusicDto2.getMusicBlob(),musicDto2.getMusicBlob());


    }

    @Test
    public void musicDtoBlobSerialisesAndDeserialisesCorrectly() throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        URL mp3Resource = classLoader.getResource("test.mp3");
        MusicDto musicDto1 = new MusicDto();
        musicDto1.setId("1_1");
        musicDto1.setFileName("filename");
        musicDto1.setMusicBlob(mp3Resource.openStream().readAllBytes());
        musicFileService.createNewMusicFile(musicDto1);
        MusicDto dbMusicDto1 = musicFileService.getMusicFileById(musicDto1.getId());

        assertEquals(dbMusicDto1.getId(),musicDto1.getId());
        assertEquals(dbMusicDto1.getFileName(),musicDto1.getFileName());
        assertEquals(dbMusicDto1.getMusicBlob(),musicDto1.getMusicBlob());

    }
}
