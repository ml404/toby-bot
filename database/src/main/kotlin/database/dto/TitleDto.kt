package database.dto

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable

@NamedQueries(
    NamedQuery(name = "TitleDto.getAll", query = "select t from TitleDto t order by t.cost asc"),
    NamedQuery(name = "TitleDto.getByLabel", query = "select t from TitleDto t where lower(t.label) = lower(:label)")
)
@Entity
@Table(name = "title", schema = "public")
@Transactional
class TitleDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "label", nullable = false, unique = true)
    var label: String = "",

    @Column(name = "cost", nullable = false)
    var cost: Long = 0,

    @Column(name = "description")
    var description: String? = null,

    @Column(name = "color_hex")
    var colorHex: String? = null,

    @Column(name = "hoisted", nullable = false)
    var hoisted: Boolean = false
) : Serializable
