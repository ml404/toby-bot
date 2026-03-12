package database.dto

import jakarta.persistence.*
import java.io.Serializable
import java.time.LocalDateTime

@NamedQueries(
    NamedQuery(
        name = "CharacterSheetDto.getById",
        query = "select c from CharacterSheetDto c where c.characterId = :characterId"
    )
)
@Entity
@Table(name = "dnd_character_sheet", schema = "public")
class CharacterSheetDto(
    @Id
    @Column(name = "character_id")
    var characterId: Long = 0,

    @Column(name = "sheet_json", nullable = false, columnDefinition = "TEXT")
    var sheetJson: String = "",

    @Column(name = "last_updated", nullable = false)
    var lastUpdated: LocalDateTime = LocalDateTime.now()
) : Serializable {

    override fun toString(): String =
        "CharacterSheetDto(characterId=$characterId, lastUpdated=$lastUpdated)"
}
