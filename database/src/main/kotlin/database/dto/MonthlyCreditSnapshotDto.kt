package database.dto

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.LocalDate

@NamedQueries(
    NamedQuery(
        name = "MonthlyCreditSnapshotDto.getForGuildDate",
        query = "select s from MonthlyCreditSnapshotDto s where s.guildId = :guildId and s.snapshotDate = :snapshotDate"
    ),
    NamedQuery(
        name = "MonthlyCreditSnapshotDto.getForUserDate",
        query = "select s from MonthlyCreditSnapshotDto s " +
                "where s.guildId = :guildId and s.discordId = :discordId and s.snapshotDate = :snapshotDate"
    )
)
@Entity
@Table(name = "monthly_credit_snapshot", schema = "public")
@IdClass(MonthlyCreditSnapshotId::class)
@Transactional
class MonthlyCreditSnapshotDto(
    @Id
    @Column(name = "discord_id")
    var discordId: Long = 0,

    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Id
    @Column(name = "snapshot_date")
    var snapshotDate: LocalDate = LocalDate.now(),

    @Column(name = "social_credit", nullable = false)
    var socialCredit: Long = 0,

    @Column(name = "toby_coins", nullable = false)
    var tobyCoins: Long = 0
) : Serializable

data class MonthlyCreditSnapshotId(
    var discordId: Long = 0,
    var guildId: Long = 0,
    var snapshotDate: LocalDate = LocalDate.now()
) : Serializable
