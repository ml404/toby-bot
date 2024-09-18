package toby.jpa.persistence.impl

import configuration.*
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.test.context.ActiveProfiles
import toby.Application
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto

@SpringBootTest(
    classes = [
        Application::class,
        TestAppConfig::class,
        TestBotConfig::class,
        TestCachingConfig::class,
        TestDatabaseConfig::class,
        TestManagerConfig::class
    ]
)
@ActiveProfiles("test")
@Transactional // Add this to ensure transactions in tests
class MusicRepositoryTest {

    @Autowired
    lateinit var entityManager: EntityManager

    @Test
    @Rollback
    fun `should return true when file is already uploaded`() {
        // Arrange: Set up the test data
        val userDto = UserDto(discordId = 123456789L, guildId = 987654321L)
        val musicDto = MusicDto(musicBlob = "musicBlob1".toByteArray(), userDto = userDto)

        // Persist the userDto and musicDto in the in-memory H2 database
        entityManager.persist(userDto)
        entityManager.persist(musicDto)
        entityManager.flush()

        // Act: Call the method to test
        val result = isFileAlreadyUploaded(musicDto)

        // Assert: Check if the result is true since the file was already uploaded
        assertTrue(result, "The file should be marked as already uploaded")
    }

    @Test
    @Rollback
    fun `should return false when file is not uploaded`() {
        // Arrange: Set up the test data with a new userDto and musicBlob
        val userDto = UserDto(discordId = 123456789L, guildId = 987654321L)
        val musicDto = MusicDto(musicBlob = "nonExistingBlob".toByteArray(), userDto = userDto)

        // Act: Call the method to test
        val result = isFileAlreadyUploaded(musicDto)

        // Assert: Check if the result is false since the file is not in the database
        assertFalse(result, "The file should not be marked as uploaded")
    }

    // Use the same method under test
    private fun isFileAlreadyUploaded(musicDto: MusicDto): Boolean =
        runCatching {
            val query = entityManager.createQuery(
                "SELECT COUNT(m) FROM MusicDto m WHERE m.musicBlob = :musicBlob AND m.userDto.discordId = :discordId AND m.userDto.guildId = :guildId",
                Long::class.java
            )
            query.setParameter("musicBlob", musicDto.musicBlob)
            query.setParameter("discordId", musicDto.userDto?.discordId)
            query.setParameter("guildId", musicDto.userDto?.guildId)

            return (query.singleResult as Long) > 0
        }.getOrElse { false }
}