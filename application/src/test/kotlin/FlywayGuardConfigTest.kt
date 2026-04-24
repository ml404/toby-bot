import database.configuration.FlywayGuardConfig
import io.mockk.every
import io.mockk.mockk
import org.flywaydb.core.api.MigrationInfo
import org.flywaydb.core.api.MigrationState
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlywayGuardConfigTest {

    @Test
    fun `throws when required and zero migrations discovered`() {
        assertThrows(IllegalStateException::class.java) {
            FlywayGuardConfig.ensureMigrationsAvailable(required = true, discovered = 0)
        }
    }

    @Test
    fun `allows boot when migrations are discovered`() {
        assertDoesNotThrow {
            FlywayGuardConfig.ensureMigrationsAvailable(required = true, discovered = 6)
        }
    }

    @Test
    fun `allows boot when guard is disabled even with zero migrations`() {
        assertDoesNotThrow {
            FlywayGuardConfig.ensureMigrationsAvailable(required = false, discovered = 0)
        }
    }

    @Test
    fun `allows boot when every migration is applied`() {
        assertDoesNotThrow {
            FlywayGuardConfig.ensureAllMigrationsApplied(
                listOf(applied("1", "base"), applied("2", "more"))
            )
        }
    }

    @Test
    fun `throws with FAILED in message when a migration failed`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            FlywayGuardConfig.ensureAllMigrationsApplied(
                listOf(applied("1", "base"), failed("13", "voice_activity_and_titles"))
            )
        }
        assertTrue(ex.message!!.contains("FAILED"))
        assertTrue(ex.message!!.contains("13"))
    }

    @Test
    fun `throws with PENDING in message when a migration is still pending`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            FlywayGuardConfig.ensureAllMigrationsApplied(
                listOf(applied("1", "base"), pending("13", "voice_activity_and_titles"))
            )
        }
        assertTrue(ex.message!!.contains("PENDING"))
        assertTrue(ex.message!!.contains("13"))
    }

    private fun applied(version: String, description: String): MigrationInfo = mockk(relaxed = true) {
        every { this@mockk.version } returns org.flywaydb.core.api.MigrationVersion.fromVersion(version)
        every { this@mockk.description } returns description
        every { state } returns MigrationState.SUCCESS
    }

    private fun failed(version: String, description: String): MigrationInfo = mockk(relaxed = true) {
        every { this@mockk.version } returns org.flywaydb.core.api.MigrationVersion.fromVersion(version)
        every { this@mockk.description } returns description
        every { state } returns MigrationState.FAILED
    }

    private fun pending(version: String, description: String): MigrationInfo = mockk(relaxed = true) {
        every { this@mockk.version } returns org.flywaydb.core.api.MigrationVersion.fromVersion(version)
        every { this@mockk.description } returns description
        every { state } returns MigrationState.PENDING
    }
}
