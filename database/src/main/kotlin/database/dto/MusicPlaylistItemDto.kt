package database.dto

import jakarta.persistence.*
import java.io.Serializable

@Entity
@Table(name = "music_playlist_item", schema = "public")
class MusicPlaylistItemDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "playlist_id", nullable = false)
    var playlist: MusicPlaylistDto? = null,

    @Column(name = "position")
    var position: Int = 0,

    @Column(name = "identifier")
    var identifier: String = "",

    @Column(name = "title")
    var title: String? = null,

    @Column(name = "author")
    var author: String? = null,

    @Column(name = "duration_ms")
    var durationMs: Long? = null,

    @Column(name = "source_name")
    var sourceName: String? = null,
) : Serializable
