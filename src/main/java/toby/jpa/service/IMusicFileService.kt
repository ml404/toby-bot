package toby.jpa.service;

import toby.jpa.dto.MusicDto;

public interface IMusicFileService {

    MusicDto createNewMusicFile(MusicDto musicDto);
    MusicDto getMusicFileById(String id);
    MusicDto updateMusicFile(MusicDto musicDto);
    void deleteMusicFile(MusicDto musicDto);
    void deleteMusicFileById(String id);

    void clearCache();

}
