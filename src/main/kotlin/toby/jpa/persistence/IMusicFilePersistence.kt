package toby.jpa.persistence

import toby.jpa.dto.MusicDto

interface IMusicFilePersistence {
    fun createNewMusicFile(musicDto: MusicDto): MusicDto?
    fun getMusicFileById(id: String): MusicDto?
    fun updateMusicFile(musicDto: MusicDto): MusicDto?
    fun deleteMusicFile(musicDto: MusicDto)
    fun deleteMusicFileById(id: String?)
    fun isFileAlreadyUploaded(musicDto: MusicDto): MusicDto?
}
