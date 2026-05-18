package database.dto

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

@NamedQueries(
    NamedQuery(name = "AchievementDto.getAll", query = "select a from AchievementDto a order by a.category, a.id"),
    NamedQuery(name = "AchievementDto.getByCode", query = "select a from AchievementDto a where a.code = :code")
)
@Entity
@Table(name = "achievement", schema = "public")
@Transactional
class AchievementDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "code", nullable = false, unique = true)
    var code: String = "",

    @Column(name = "name", nullable = false)
    var name: String = "",

    @Column(name = "description", nullable = false)
    var description: String = "",

    @Column(name = "category", nullable = false)
    var category: String = "",

    @Column(name = "icon")
    var icon: String? = null,

    @Column(name = "xp_reward", nullable = false)
    var xpReward: Int = 0,

    @Column(name = "credit_reward", nullable = false)
    var creditReward: Long = 0,

    @Column(name = "threshold", nullable = false)
    var threshold: Long = 1,

    @Column(name = "hidden", nullable = false)
    var hidden: Boolean = false,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
) : Serializable
