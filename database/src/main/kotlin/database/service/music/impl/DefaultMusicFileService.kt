package database.service.music.impl

import common.events.IntroSetEvent
import database.dto.MusicDto
import database.persistence.music.MusicFilePersistence
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

@Service
class DefaultMusicFileService(
    private val eventPublisher: ApplicationEventPublisher? = null
) : database.service.music.MusicFileService {
    @Autowired
    private lateinit var musicFileService: MusicFilePersistence


    @CachePut(value = ["music"], key = "#musicDto.id")
    override fun createNewMusicFile(musicDto: MusicDto): MusicDto? {
        val saved = musicFileService.createNewMusicFile(musicDto)
        if (saved != null) {
            val user = saved.userDto ?: musicDto.userDto
            if (user != null) {
                eventPublisher?.publishEvent(
                    IntroSetEvent(discordId = user.discordId, guildId = user.guildId)
                )
            }
        }
        return saved
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
