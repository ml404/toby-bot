package database.service.user.impl

import database.dto.user.SharedCubeDto
import database.persistence.user.SharedCubePersistence
import database.service.user.SharedCubeService
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Instant

@Service
class DefaultSharedCubeService(
    private val persistence: SharedCubePersistence,
) : SharedCubeService {

    private val random = SecureRandom()

    override fun create(discordId: Long, name: String, cards: String, at: Instant): SharedCubeDto {
        val token = freshToken()
        return persistence.insert(
            SharedCubeDto(
                token = token,
                discordId = discordId,
                name = name,
                cards = cards,
                createdAt = at,
            )
        )
    }

    override fun get(token: String): SharedCubeDto? = persistence.get(token)

    /** A random token not already taken (collisions are astronomically rare). */
    private fun freshToken(): String {
        repeat(MAX_TRIES) {
            val token = randomToken()
            if (persistence.get(token) == null) return token
        }
        // Exhausting MAX_TRIES against a 62^10 space is effectively impossible;
        // surface it rather than silently loop forever.
        throw IllegalStateException("Could not allocate a unique share token.")
    }

    private fun randomToken(): String =
        buildString(TOKEN_LENGTH) {
            repeat(TOKEN_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }

    private companion object {
        const val TOKEN_LENGTH = 10
        const val MAX_TRIES = 5
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
