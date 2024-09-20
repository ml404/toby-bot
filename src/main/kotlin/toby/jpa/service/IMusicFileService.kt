package toby.jpa.service

import toby.jpa.dto.MusicDto

interface IMusicFileService {
    fun createNewMusicFile(musicDto: MusicDto): MusicDto?
    fun getMusicFileById(id: String): MusicDto?
    fun updateMusicFile(musicDto: MusicDto): MusicDto?
    fun deleteMusicFile(musicDto: MusicDto)
    fun deleteMusicFileById(id: String?)
    fun clearCache()
    fun isFileAlreadyUploaded(musicDto: MusicDto): MusicDto?

}
