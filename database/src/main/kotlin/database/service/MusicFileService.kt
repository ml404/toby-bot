package database.service

import database.dto.MusicDto

interface MusicFileService {
    fun createNewMusicFile(musicDto: MusicDto): MusicDto?
    fun getMusicFileById(id: String): MusicDto?
    fun updateMusicFile(musicDto: MusicDto): MusicDto?
    fun deleteMusicFile(musicDto: MusicDto)
    fun deleteMusicFileById(id: String?)
    fun clearCache()
    fun isFileAlreadyUploaded(musicDto: MusicDto): MusicDto?
}
