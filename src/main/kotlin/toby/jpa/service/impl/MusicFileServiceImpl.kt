package toby.jpa.service.impl

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.stereotype.Service
import toby.jpa.dto.MusicDto
import toby.jpa.persistence.IMusicFilePersistence
import toby.jpa.service.IMusicFileService

@Service
open class MusicFileServiceImpl : IMusicFileService {
    @Autowired
    lateinit var musicFileService: IMusicFilePersistence


    @CachePut(value = ["music"], key = "#musicDto.id")
    override fun createNewMusicFile(musicDto: MusicDto): MusicDto? {
        return musicFileService.createNewMusicFile(musicDto)
    }

    @CachePut(value = ["music"], key = "#id")
    override fun getMusicFileById(id: String): MusicDto? {
        return musicFileService.getMusicFileById(id)
    }

    @CachePut(value = ["music"], key = "#musicDto.id")
    override fun updateMusicFile(musicDto: MusicDto): MusicDto? {
        return musicFileService.updateMusicFile(musicDto)
    }

    @CacheEvict(value = ["music"], key = "#musicDto.id")
    override fun deleteMusicFile(musicDto: MusicDto) {
        musicFileService.deleteMusicFile(musicDto)
    }

    @CacheEvict(value = ["music"], key = "#id")
    override fun deleteMusicFileById(id: String?) {
        musicFileService.deleteMusicFileById(id)
    }

    @CacheEvict(value = ["music"], allEntries = true)
    override fun clearCache() {
    }

    override fun isFileAlreadyUploaded(musicDto: MusicDto): Boolean {
        return musicFileService.isFileAlreadyUploaded(musicDto)
    }
}
