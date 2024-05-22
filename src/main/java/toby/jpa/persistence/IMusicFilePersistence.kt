package toby.jpa.persistence;

import toby.jpa.dto.MusicDto;

public interface IMusicFilePersistence {

    MusicDto createNewMusicFile(MusicDto musicDto);
    MusicDto getMusicFileById(String id);
    MusicDto updateMusicFile(MusicDto musicDto);
    void deleteMusicFile(MusicDto musicDto);
    void deleteMusicFileById(String id);
}
