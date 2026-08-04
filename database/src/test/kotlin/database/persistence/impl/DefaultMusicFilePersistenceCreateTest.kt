package database.persistence.impl

import database.dto.music.MusicDto
import database.dto.user.UserDto
import database.persistence.music.impl.DefaultMusicFilePersistence
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.persistence.EntityManager
import jakarta.persistence.TypedQuery
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The last line of defence against the overwrite that prompted this.
 *
 * An intro's id is `guildId_discordId_slot`, so a row already at that id is the
 * intro living in that slot. `createNewMusicFile` used to fall through to
 * `updateMusicFile` on a hit, merging the new track over the old one: the user
 * was told their intro saved, and the one it landed on was gone, with nothing
 * logged and nothing to recover from.
 */
class DefaultMusicFilePersistenceCreateTest {

    private lateinit var em: EntityManager
    private lateinit var persistence: DefaultMusicFilePersistence

    private val user = UserDto(discordId = 7L, guildId = 42L)

    @BeforeEach
    fun setUp() {
        em = mockk(relaxed = true)
        // No prior upload with this content hash unless a test says otherwise.
        val dedupeQuery = mockk<TypedQuery<MusicDto>>(relaxed = true)
        every { em.createQuery(any<String>(), MusicDto::class.java) } returns dedupeQuery
        every { dedupeQuery.setParameter(any<String>(), any()) } returns dedupeQuery
        every { dedupeQuery.resultList } returns emptyList()

        persistence = DefaultMusicFilePersistence().apply {
            DefaultMusicFilePersistence::class.java
                .getDeclaredField("entityManager")
                .apply { isAccessible = true }
                .set(this, em)
        }
    }

    private fun intro(slot: Int) = MusicDto(user, slot, "track$slot", 90, byteArrayOf(slot.toByte()))

    @Test
    fun `a free slot is persisted`() {
        every { em.find(MusicDto::class.java, any()) } returns null
        val fresh = intro(2)

        val saved = persistence.createNewMusicFile(fresh)

        assertSame(fresh, saved)
        verify { em.persist(fresh) }
    }

    @Test
    fun `an occupied slot is refused rather than overwritten`() {
        val existing = intro(3)
        every { em.find(MusicDto::class.java, existing.id) } returns existing
        val replacement = MusicDto(user, 3, "someone else's track", 90, byteArrayOf(9))

        val saved = persistence.createNewMusicFile(replacement)

        assertNull(saved, "a collision must not report success")
        verify(exactly = 0) { em.persist(any()) }
        verify(exactly = 0) { em.merge(any()) }
    }

    @Test
    fun `the intro already in the slot is left exactly as it was`() {
        val existing = intro(1).apply { fileName = "mine"; playCount = 12 }
        every { em.find(MusicDto::class.java, existing.id) } returns existing

        persistence.createNewMusicFile(MusicDto(user, 1, "theirs", 50, byteArrayOf(9)))

        assertNotNull(existing.fileName)
        assertSame("mine", existing.fileName)
        assertSame(12, existing.playCount)
    }

    @Test
    fun `a duplicate upload is still refused before the slot is even looked at`() {
        val dedupeQuery = mockk<TypedQuery<MusicDto>>(relaxed = true)
        every { em.createQuery(any<String>(), MusicDto::class.java) } returns dedupeQuery
        every { dedupeQuery.setParameter(any<String>(), any()) } returns dedupeQuery
        every { dedupeQuery.resultList } returns listOf(intro(1))

        assertNull(persistence.createNewMusicFile(intro(2)))
        verify(exactly = 0) { em.persist(any()) }
    }
}
