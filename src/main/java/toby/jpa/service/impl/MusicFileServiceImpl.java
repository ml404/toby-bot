package toby.jpa.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import toby.jpa.dto.MusicDto;
import toby.jpa.persistence.IMusicFilePersistence;
import toby.jpa.service.IMusicFileService;

@Service
public class MusicFileServiceImpl implements IMusicFileService {

    @Autowired
    IMusicFilePersistence musicFileService;


    @Override
    @CachePut(value = "music", key = "#musicDto.id")
    public MusicDto createNewMusicFile(MusicDto musicDto) {
        return musicFileService.createNewMusicFile(musicDto);
    }

    @Override
    @Cacheable(value = "music", key = "#id")
    public MusicDto getMusicFileById(String id) {
        return musicFileService.getMusicFileById(id);
    }

    @Override
    @CachePut(value = "music", key = "#musicDto.id")
    public MusicDto updateMusicFile(MusicDto musicDto) {
        return musicFileService.updateMusicFile(musicDto);
    }

    @Override
    @CacheEvict(value = "music", key = "#musicDto.id")
    public void deleteMusicFile(MusicDto musicDto) {
        musicFileService.deleteMusicFile(musicDto);
    }

    @Override
    @CacheEvict(value = "music", key = "#id")
    public void deleteMusicFileById(String id) {
        musicFileService.deleteMusicFileById(id);
    }
}
