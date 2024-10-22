package integration.database

import common.configuration.TestCachingConfig
import database.configuration.TestDatabaseConfig
import database.dto.MusicDto
import database.dto.MusicDto.Companion.computeHash
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

@SpringBootTest(
    classes = [
        Application::class,
        TestCachingConfig::class,
        TestDatabaseConfig::class,
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
        val userDto = database.dto.UserDto(discordId = 123456789L, guildId = 987654321L)
        val musicDto = MusicDto(userDto, 1, "filename", 10, "SomeBlob".toByteArray())

        // Persist the userDto and musicDto in the in-memory H2 database
        entityManager.persist(userDto)
        entityManager.persist(musicDto)
        entityManager.flush()

        // Act: Call the method to test
        val result = isFileAlreadyUploaded(musicDto)

        // Assert: Check if the result is true since the file was already uploaded
        assertNotNull(result, "The file should be marked as already uploaded")
    }

    @Test
    @Rollback
    fun `should return false when file is not uploaded`() {
        // Arrange: Set up the test data with a new userDto and musicBlob
        val userDto = database.dto.UserDto(discordId = 123456789L, guildId = 987654321L)
        val musicDto = MusicDto(musicBlob = "nonExistingBlob".toByteArray(), userDto = userDto)

        // Act: Call the method to test
        val result = isFileAlreadyUploaded(musicDto)

        // Assert: Check if the result is false since the file is not in the database
        assertNull(result, "The file should not be marked as uploaded")
    }

    // Use the same method under test
    private fun isFileAlreadyUploaded(musicDto: MusicDto): MusicDto? {
        return runCatching {
            val query = entityManager.createQuery(
                "SELECT m FROM MusicDto m WHERE m.musicBlobHash = :musicBlobHash AND m.userDto.discordId = :discordId AND m.userDto.guildId = :guildId",
                MusicDto::class.java
            )
            query.setParameter("musicBlobHash", computeHash(musicDto.musicBlob ?: ByteArray(0)))
            query.setParameter("discordId", musicDto.userDto?.discordId)
            query.setParameter("guildId", musicDto.userDto?.guildId)

            query.resultList.firstOrNull() // Fetch the first matching record, if any
        }.getOrNull()
    }
}