package web.service.sse

import com.github.benmanes.caffeine.cache.Ticker
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Covers the shared SSE plumbing every keyed channel (music, notifications)
 * delegates to. Two failure-mode families need real attention:
 *
 *  - the lifecycle callbacks (onCompletion / onTimeout / onError) must
 *    evict the offending emitter AND drop the whole bucket when its last
 *    occupant leaves — this is what keeps a per-discordId registry
 *    bounded across the JVM lifetime.
 *  - the broadcast-time catch must evict emitters that throw IOException /
 *    IllegalStateException on send (the "race: client gone, callback
 *    hasn't fired yet" case) so a single dead subscriber doesn't keep
 *    poisoning every fanOut.
 *
 * Tests inject mocked SseEmitters through the [KeyedSseRegistry.register]
 * default-arg seam so the broken-pipe case can be exercised without a
 * real servlet response.
 */
class KeyedSseRegistryTest {

    /**
     * A relaxed-mock SseEmitter. `relaxUnitFun = true` so the no-op
     * lifecycle callback registrations don't need explicit stubs.
     */
    private fun mockEmitter(
        sendThrows: Throwable? = null,
    ): SseEmitter = mockk(relaxed = true, relaxUnitFun = true) {
        if (sendThrows != null) {
            every { send(any<SseEmitter.SseEventBuilder>()) } throws sendThrows
        }
    }

    @Test
    fun `register returns the same emitter it was handed and stores it under the key`() {
        val registry = KeyedSseRegistry<Long>(executor = Runnable::run)
        val em = mockEmitter()
        val returned = registry.register(1L, emptyMap<String, Any>(), em)
        assertSame(em, returned)
        assertFalse(registry.bucketIsEmpty(1L))
        assertTrue(registry.activeKeysSnapshot().contains(1L))
    }

    @Test
    fun `register sends a hello event with the supplied payload`() {
        val registry = KeyedSseRegistry<Long>(executor = Runnable::run)
        val em = mockEmitter()
        val payload = mapOf("discordId" to 42L)
        registry.register(42L, payload, em)
        verify(exactly = 1) { em.send(any<SseEmitter.SseEventBuilder>()) }
    }

    @Test
    fun `register wires onCompletion onTimeout and onError lifecycle callbacks`() {
        val registry = KeyedSseRegistry<Long>(executor = Runnable::run)
        val em = mockEmitter()
        registry.register(99L, emptyMap<String, Any>(), em)
        verify(exactly = 1) { em.onCompletion(any()) }
        verify(exactly = 1) { em.onTimeout(any()) }
        verify(exactly = 1) { em.onError(any()) }
    }

    @Test
    fun `hello-send failure evicts the freshly-registered emitter`() {
        val registry = KeyedSseRegistry<Long>(executor = Runnable::run)
        val em = mockEmitter(sendThrows = IOException("client closed pre-hello"))
        registry.register(10L, emptyMap<String, Any>(), em)
        // The hello send failed; the registry should have evicted the
        // emitter through its onFailure branch.
        assertTrue(
            registry.bucketIsEmpty(10L),
            "Failed hello must evict the emitter so the bucket doesn't carry a dead subscriber.",
        )
    }

    @Test
    fun `multiple registrations under the same key coexist in the bucket`() {
        val registry = KeyedSseRegistry<Long>(executor = Runnable::run)
        registry.register(7L, emptyMap<String, Any>(), mockEmitter())
        registry.register(7L, emptyMap<String, Any>(), mockEmitter())
        registry.register(7L, emptyMap<String, Any>(), mockEmitter())
        registry.fanOut(7L, "test", mapOf("n" to 3))
        // Single key entry, but multiple emitters in the bucket.
        assertEquals(setOf(7L), registry.activeKeysSnapshot())
        assertFalse(registry.bucketIsEmpty(7L))
    }

    @Test
    fun `fanOut sends the event to every emitter under the key`() {
        val registry = KeyedSseRegistry<Long>(executor = Runnable::run)
        val a = mockEmitter()
        val b = mockEmitter()
        registry.register(3L, emptyMap<String, Any>(), a)
        registry.register(3L, emptyMap<String, Any>(), b)
        registry.fanOut(3L, "test", mapOf("k" to "v"))
        // 1 hello + 1 broadcast = 2 sends per emitter.
        verify(exactly = 2) { a.send(any<SseEmitter.SseEventBuilder>()) }
        verify(exactly = 2) { b.send(any<SseEmitter.SseEventBuilder>()) }
    }

    @Test
    fun `fanOut to a different key does not touch the other bucket's emitters`() {
        val registry = KeyedSseRegistry<Long>(executor = Runnable::run)
        val a = mockEmitter()
        val b = mockEmitter()
        registry.register(1L, emptyMap<String, Any>(), a)
        registry.register(2L, emptyMap<String, Any>(), b)
        registry.fanOut(1L, "test", mapOf<String, Any>())
        // a gets hello + broadcast = 2; b gets hello only = 1.
        verify(exactly = 2) { a.send(any<SseEmitter.SseEventBuilder>()) }
        verify(exactly = 1) { b.send(any<SseEmitter.SseEventBuilder>()) }
    }

    @Test
    fun `fanOut to an unknown key is a silent no-op`() {
        val registry = KeyedSseRegistry<Long>(executor = Runnable::run)
        registry.fanOut(404L, "test", mapOf<String, Any>())
        assertTrue(registry.activeKeysSnapshot().isEmpty())
    }

    @Test
    fun `IOException on fanOut send evicts the emitter from the bucket`() {
        val registry = KeyedSseRegistry<Long>(executor = Runnable::run)
        // The throwing emitter survives the hello-send because the
        // mock throws on EVERY send; the registry's onFailure path
        // evicts it. So we register a healthy emitter, then a broken
        // one whose hello-send also fails — but we want a third path:
        // healthy emitter, then THE SAME healthy emitter starts throwing
        // mid-fanOut. Simulate by registering the mock with the throw
        // configured AFTER hello.
        val healthy = mockEmitter()
        registry.register(50L, emptyMap<String, Any>(), healthy)
        val broken = mockk<SseEmitter>(relaxed = true, relaxUnitFun = true).apply {
            // First send (hello) succeeds, every subsequent send throws.
            var first = true
            every { send(any<SseEmitter.SseEventBuilder>()) } answers {
                if (first) { first = false } else throw IOException("broken pipe")
            }
        }
        registry.register(50L, emptyMap<String, Any>(), broken)
        // Both emitters in the bucket. fanOut will throw on `broken`,
        // catch via the broadcast helper, and evict it.
        registry.fanOut(50L, "test", mapOf<String, Any>())
        // healthy survives; broken is evicted.
        assertFalse(registry.bucketIsEmpty(50L))
        // A second fanOut should only touch healthy. healthy receives:
        // hello + fanOut1 + fanOut2 = 3 sends total. broken received:
        // hello + fanOut1 = 2.
        registry.fanOut(50L, "test", mapOf<String, Any>())
        verify(exactly = 3) { healthy.send(any<SseEmitter.SseEventBuilder>()) }
        verify(exactly = 2) { broken.send(any<SseEmitter.SseEventBuilder>()) }
    }

    @Test
    fun `IllegalStateException on fanOut send evicts the emitter from the bucket`() {
        val registry = KeyedSseRegistry<Long>(executor = Runnable::run)
        val healthy = mockEmitter()
        registry.register(60L, emptyMap<String, Any>(), healthy)
        val broken = mockk<SseEmitter>(relaxed = true, relaxUnitFun = true).apply {
            var first = true
            every { send(any<SseEmitter.SseEventBuilder>()) } answers {
                if (first) { first = false } else throw IllegalStateException("already completed")
            }
        }
        registry.register(60L, emptyMap<String, Any>(), broken)
        registry.fanOut(60L, "test", mapOf<String, Any>())
        registry.fanOut(60L, "test", mapOf<String, Any>())
        verify(exactly = 3) { healthy.send(any<SseEmitter.SseEventBuilder>()) }
        verify(exactly = 2) { broken.send(any<SseEmitter.SseEventBuilder>()) }
    }

    @Test
    fun `dropping the last emitter in a bucket via broadcast eviction removes the bucket entirely`() {
        val registry = KeyedSseRegistry<Long>(executor = Runnable::run)
        val broken = mockk<SseEmitter>(relaxed = true, relaxUnitFun = true).apply {
            var first = true
            every { send(any<SseEmitter.SseEventBuilder>()) } answers {
                if (first) { first = false } else throw IOException("gone")
            }
        }
        registry.register(70L, emptyMap<String, Any>(), broken)
        registry.fanOut(70L, "test", mapOf<String, Any>())
        // Last-emitter eviction must drop the bucket — the per-user
        // memory invariant the whole Caffeine setup is built around.
        assertTrue(
            registry.bucketIsEmpty(70L),
            "Sole broken emitter evicted via broadcast → bucket must be dropped from the registry.",
        )
        assertFalse(registry.activeKeysSnapshot().contains(70L))
    }

    @Test
    fun `evict completes every emitter in the bucket via the removal listener`() {
        val registry = KeyedSseRegistry<Long>(executor = Runnable::run)
        val a = mockEmitter()
        val b = mockEmitter()
        registry.register(80L, emptyMap<String, Any>(), a)
        registry.register(80L, emptyMap<String, Any>(), b)
        registry.evict(80L)
        verify(exactly = 1) { a.complete() }
        verify(exactly = 1) { b.complete() }
        assertTrue(registry.bucketIsEmpty(80L))
    }

    @Test
    fun `evict on a never-registered key is a silent no-op`() {
        val registry = KeyedSseRegistry<Long>(executor = Runnable::run)
        registry.evict(9999L)
        assertTrue(registry.bucketIsEmpty(9999L))
    }

    @Test
    fun `heartbeat does not throw when there are no subscribers`() {
        val registry = KeyedSseRegistry<Long>(executor = Runnable::run)
        registry.heartbeat()
    }

    @Test
    fun `heartbeat ticks every active emitter exactly once`() {
        val registry = KeyedSseRegistry<Long>(executor = Runnable::run)
        val a = mockEmitter()
        val b = mockEmitter()
        val c = mockEmitter()
        registry.register(91L, emptyMap<String, Any>(), a)
        registry.register(92L, emptyMap<String, Any>(), b)
        registry.register(92L, emptyMap<String, Any>(), c)
        registry.heartbeat()
        // Each emitter: 1 hello + 1 heartbeat = 2 sends.
        verify(exactly = 2) { a.send(any<SseEmitter.SseEventBuilder>()) }
        verify(exactly = 2) { b.send(any<SseEmitter.SseEventBuilder>()) }
        verify(exactly = 2) { c.send(any<SseEmitter.SseEventBuilder>()) }
    }

    @Test
    fun `heartbeat evicts dead emitters mid-broadcast`() {
        val registry = KeyedSseRegistry<Long>(executor = Runnable::run)
        val healthy = mockEmitter()
        registry.register(100L, emptyMap<String, Any>(), healthy)
        val broken = mockk<SseEmitter>(relaxed = true, relaxUnitFun = true).apply {
            var first = true
            every { send(any<SseEmitter.SseEventBuilder>()) } answers {
                if (first) { first = false } else throw IOException("dead")
            }
        }
        registry.register(100L, emptyMap<String, Any>(), broken)
        registry.heartbeat()
        // healthy survives, broken is evicted.
        assertFalse(registry.bucketIsEmpty(100L))
        registry.heartbeat()
        verify(exactly = 3) { healthy.send(any<SseEmitter.SseEventBuilder>()) }
        verify(exactly = 2) { broken.send(any<SseEmitter.SseEventBuilder>()) }
    }

    @Test
    fun `bucketIsEmpty returns true for never-registered keys`() {
        val registry = KeyedSseRegistry<Long>(executor = Runnable::run)
        assertTrue(registry.bucketIsEmpty(999L))
    }

    @Test
    fun `bucketIsEmpty returns false for active keys`() {
        val registry = KeyedSseRegistry<Long>(executor = Runnable::run)
        registry.register(51L, emptyMap<String, Any>(), mockEmitter())
        assertFalse(registry.bucketIsEmpty(51L))
    }

    @Test
    fun `forEachActiveKey visits exactly the registered keys`() {
        val registry = KeyedSseRegistry<Long>(executor = Runnable::run)
        registry.register(101L, emptyMap<String, Any>(), mockEmitter())
        registry.register(102L, emptyMap<String, Any>(), mockEmitter())
        registry.register(103L, emptyMap<String, Any>(), mockEmitter())
        val visited = mutableSetOf<Long>()
        registry.forEachActiveKey { visited.add(it) }
        assertEquals(setOf(101L, 102L, 103L), visited)
    }

    @Test
    fun `maximumSize cap evicts buckets and completes their emitters when exceeded`() {
        val registry = KeyedSseRegistry<Long>(
            maximumKeys = 2L,
            idleBucketTtl = 1L,
            idleBucketTtlUnit = TimeUnit.HOURS,
            executor = Runnable::run,
        )
        val a = mockEmitter()
        val b = mockEmitter()
        val c = mockEmitter()
        registry.register(1L, emptyMap<String, Any>(), a)
        registry.register(2L, emptyMap<String, Any>(), b)
        registry.register(3L, emptyMap<String, Any>(), c)
        registry.cleanUp()
        val remaining = registry.activeKeysSnapshot()
        assertEquals(
            2,
            remaining.size,
            "Cache cap must hold the registry to its configured maximum.",
        )
    }

    @Test
    fun `expireAfterAccess evicts idle buckets and completes their emitters`() {
        val nanos = AtomicLong(0L)
        val registry = KeyedSseRegistry<Long>(
            maximumKeys = 1000L,
            idleBucketTtl = 100L,
            idleBucketTtlUnit = TimeUnit.MILLISECONDS,
            ticker = Ticker { nanos.get() },
            executor = Runnable::run,
        )
        val em = mockEmitter()
        registry.register(301L, emptyMap<String, Any>(), em)
        nanos.set(TimeUnit.MILLISECONDS.toNanos(500L))
        registry.cleanUp()
        assertTrue(
            registry.bucketIsEmpty(301L),
            "Idle bucket must be expired once the access TTL has passed.",
        )
        // The removal listener must complete the survivor so the
        // network resources are released, not just the registry slot.
        verify(exactly = 1) { em.complete() }
    }

    @Test
    fun `fanOut refreshes the access timer so active subscribers don't get reaped`() {
        val nanos = AtomicLong(0L)
        val registry = KeyedSseRegistry<Long>(
            maximumKeys = 1000L,
            idleBucketTtl = 100L,
            idleBucketTtlUnit = TimeUnit.MILLISECONDS,
            ticker = Ticker { nanos.get() },
            executor = Runnable::run,
        )
        registry.register(401L, emptyMap<String, Any>(), mockEmitter())
        nanos.set(TimeUnit.MILLISECONDS.toNanos(80L))
        registry.fanOut(401L, "ping", mapOf<String, Any>())
        nanos.set(TimeUnit.MILLISECONDS.toNanos(150L))
        registry.cleanUp()
        assertFalse(
            registry.bucketIsEmpty(401L),
            "fanOut must refresh access time so an active subscriber isn't reaped.",
        )
    }

    @Test
    fun `generic registry works with non-Long key types`() {
        val registry = KeyedSseRegistry<String>(executor = Runnable::run)
        registry.register("alice", emptyMap<String, Any>(), mockEmitter())
        registry.register("bob", emptyMap<String, Any>(), mockEmitter())
        assertEquals(setOf("alice", "bob"), registry.activeKeysSnapshot())
        registry.fanOut("alice", "ping", mapOf<String, Any>())
        registry.evict("alice")
        assertEquals(setOf("bob"), registry.activeKeysSnapshot())
    }
}
