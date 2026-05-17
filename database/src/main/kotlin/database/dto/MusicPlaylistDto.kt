package database.dto

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

@NamedQueries(
    NamedQuery(
        name = "MusicPlaylistDto.getByGuild",
        query = "select p from MusicPlaylistDto p WHERE p.guildId = :guildId ORDER BY LOWER(p.name) ASC"
    ),
    NamedQuery(
        name = "MusicPlaylistDto.getByGuildAndOwner",
        query = "select p from MusicPlaylistDto p WHERE p.guildId = :guildId AND p.ownerDiscordId = :ownerId ORDER BY LOWER(p.name) ASC"
    ),
    NamedQuery(
        name = "MusicPlaylistDto.getByGuildOwnerAndName",
        query = "select p from MusicPlaylistDto p WHERE p.guildId = :guildId AND p.ownerDiscordId = :ownerId AND LOWER(p.name) = LOWER(:name)"
    ),
    NamedQuery(
        name = "MusicPlaylistDto.getById",
        query = "select p from MusicPlaylistDto p WHERE p.id = :id"
    ),
    NamedQuery(
        name = "MusicPlaylistDto.deleteById",
        query = "delete from MusicPlaylistDto p WHERE p.id = :id"
    ),
)
@Entity
@Table(name = "music_playlist", schema = "public")
@Transactional
class MusicPlaylistDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "guild_id")
    var guildId: Long = 0L,

    @Column(name = "owner_discord_id")
    var ownerDiscordId: Long = 0L,

    @Column(name = "name")
    var name: String = "",

    @Column(name = "created_at")
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null,

    @OneToMany(
        mappedBy = "playlist",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.EAGER,
    )
    @OrderBy("position ASC")
    var items: MutableList<MusicPlaylistItemDto> = mutableListOf(),
) : Serializable {

    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        if (createdAt == null) createdAt = now
        if (updatedAt == null) updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }
}
