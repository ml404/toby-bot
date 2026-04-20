import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationInfo
import org.flywaydb.core.api.MigrationInfoService
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FlywayGuardConfigTest {

    private fun flywayWith(migrationCount: Int): Flyway {
        val flyway = mockk<Flyway>(relaxed = true)
        val infoService = mockk<MigrationInfoService>()
        val infos: Array<MigrationInfo> = Array(migrationCount) { mockk(relaxed = true) }
        every { flyway.info() } returns infoService
        every { infoService.all() } returns infos
        return flyway
    }

    @Test
    fun `strategy fails fast when classpath has zero migrations and guard is required`() {
        val strategy = FlywayGuardConfig().flywayMigrationStrategy(required = true)
        val flyway = flywayWith(migrationCount = 0)

        assertThrows(IllegalStateException::class.java) { strategy.migrate(flyway) }
        verify(exactly = 0) { flyway.migrate() }
    }

    @Test
    fun `strategy migrates normally when migrations are present`() {
        val strategy = FlywayGuardConfig().flywayMigrationStrategy(required = true)
        val flyway = flywayWith(migrationCount = 6)

        strategy.migrate(flyway)

        verify(exactly = 1) { flyway.migrate() }
    }

    @Test
    fun `strategy skips the guard when required is false`() {
        val strategy = FlywayGuardConfig().flywayMigrationStrategy(required = false)
        val flyway = flywayWith(migrationCount = 0)

        strategy.migrate(flyway)

        verify(exactly = 1) { flyway.migrate() }
    }
}
