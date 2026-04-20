import database.configuration.FlywayGuardConfig
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
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
}
