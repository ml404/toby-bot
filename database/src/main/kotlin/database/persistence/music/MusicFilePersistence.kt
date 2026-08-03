package database.persistence.music

import database.dto.music.MusicDto

interface MusicFilePersistence {
    fun createNewMusicFile(musicDto: MusicDto): MusicDto?
    fun getMusicFileById(id: String): MusicDto?
    fun updateMusicFile(musicDto: MusicDto): MusicDto?
    fun deleteMusicFile(musicDto: MusicDto)
    fun deleteMusicFileById(id: String?)
    fun isFileAlreadyUploaded(musicDto: MusicDto): MusicDto?

    /**
     * Aggregate report for one guild's intros.
     *
     * Deliberately a projection rather than `List<MusicDto>`: the interesting
     * total is how many bytes of audio the guild is storing, and loading every
     * row to add up `musicBlob.size` would pull those bytes into heap to
     * measure them — on a 512 MB dyno, for the one query whose whole purpose
     * is to warn that they're adding up.
     */
    fun getGuildIntroStats(guildId: Long, unhealthyThreshold: Int): GuildIntroStats
}

/**
 * @param introCount rows stored for the guild.
 * @param userCount distinct members holding at least one.
 * @param storedBytes total size of the stored audio blobs. URL intros hold
 *        only the URL, so this is effectively "how much uploaded audio".
 * @param totalPlays lifetime plays summed across the guild's intros.
 * @param disabledCount intros switched out of the rotation by their owner.
 * @param brokenCount intros whose consecutive-failure count has crossed the
 *        unhealthy threshold — the number an admin would actually act on.
 */
data class GuildIntroStats(
    val introCount: Long = 0,
    val userCount: Long = 0,
    val storedBytes: Long = 0,
    val totalPlays: Long = 0,
    val disabledCount: Long = 0,
    val brokenCount: Long = 0,
)
