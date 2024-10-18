package database.service.impl

import database.dto.MusicDto
import database.persistence.MusicFilePersistence
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.stereotype.Service

@Service
open class DefaultMusicFileService : database.service.MusicFileService {
    @Autowired
    lateinit var musicFileService: MusicFilePersistence


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

    override fun isFileAlreadyUploaded(musicDto: MusicDto): MusicDto? {
        return musicFileService.isFileAlreadyUploaded(musicDto)
    }
}
