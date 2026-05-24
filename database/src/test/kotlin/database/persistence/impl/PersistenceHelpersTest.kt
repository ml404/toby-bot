package database.persistence.impl

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import database.persistence.saveOrMerge

class PersistenceHelpersTest {

    private data class Row(var id: Long?, var label: String, var stampedAt: Long? = null)

    @Test
    fun `new entity is persisted then flushed and returned as-is`() {
        val em = mockk<EntityManager>(relaxed = true)
        val row = Row(id = null, label = "new")

        val saved = em.saveOrMerge(row, isNew = { it.id == null })

        assertSame(row, saved)
        verifyOrder {
            em.persist(row)
            em.flush()
        }
        verify(exactly = 0) { em.merge(any<Row>()) }
    }

    @Test
    fun `existing entity is merged then flushed and returns the merge result`() {
        val em = mockk<EntityManager>(relaxed = true)
        val row = Row(id = 42L, label = "existing")
        val mergedCopy = row.copy(label = "fromMerge")
        every { em.merge(row) } returns mergedCopy
        every { em.flush() } just Runs

        val saved = em.saveOrMerge(row, isNew = { it.id == null })

        assertSame(mergedCopy, saved)
        assertNotSame(row, saved)
        verifyOrder {
            em.merge(row)
            em.flush()
        }
        verify(exactly = 0) { em.persist(any()) }
    }

    @Test
    fun `onCreate runs once on the new branch, before persist`() {
        val em = mockk<EntityManager>(relaxed = true)
        val row = Row(id = null, label = "stamp-me")
        val stampOrder = mutableListOf<String>()
        every { em.persist(row) } answers { stampOrder.add("persist"); Unit }

        em.saveOrMerge(
            row,
            isNew = { it.id == null },
            onCreate = { it.stampedAt = 123L; stampOrder.add("onCreate") }
        )

        assertEquals(123L, row.stampedAt)
        assertEquals(listOf("onCreate", "persist"), stampOrder)
    }

    @Test
    fun `onCreate is skipped on the merge branch`() {
        val em = mockk<EntityManager>(relaxed = true)
        val row = Row(id = 7L, label = "existing")
        every { em.merge(row) } returns row
        every { em.flush() } just Runs
        var onCreateCalls = 0

        em.saveOrMerge(
            row,
            isNew = { it.id == null },
            onCreate = { onCreateCalls++ }
        )

        assertEquals(0, onCreateCalls)
    }

    @Test
    fun `isNew predicate decides the branch independent of id heuristics`() {
        // Use a custom predicate that always reports "existing" even when id is null.
        val em = mockk<EntityManager>(relaxed = true)
        val row = Row(id = null, label = "force-merge")
        every { em.merge(row) } returns row
        every { em.flush() } just Runs

        em.saveOrMerge(row, isNew = { false })

        verify { em.merge(row) }
        verify(exactly = 0) { em.persist(any()) }
    }
}
