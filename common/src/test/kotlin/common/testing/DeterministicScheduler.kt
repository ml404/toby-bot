package common.testing

import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Test-only [ScheduledExecutorService] that captures scheduled tasks but
 * does not run them on a wall-clock timer. Tests advance work by calling
 * [runPending], which fires every still-live task on the calling thread
 * and clears the queue.
 *
 * Why this exists: the production schedulers in `NowPlayingManager`,
 * `PokerTableRegistry`, and `PendingDuelRegistry` are normally a real
 * `ScheduledThreadPoolExecutor`. The corresponding tests previously
 * relied on `Thread.sleep` + `latch.await(2-3 sec)` to wait for the real
 * scheduler to fire — that's slow (multiple seconds per file under
 * normal load), and worse, becomes a deadlock under JUnit 5 in-JVM
 * parallel execution because the executor thread can be starved by the
 * concurrent runner. See the bug audit in #365.
 *
 * Cancelling a returned future removes the pending task so a subsequent
 * [runPending] won't fire it. [shutdownNow] drains and returns any
 * still-pending tasks (matching the production contract).
 *
 * Periodic tasks (`scheduleAtFixedRate`, `scheduleWithFixedDelay`) fire
 * exactly once per [runPending] call, then must be re-scheduled by
 * production code (none of our schedulers self-rearm, so this is a non-
 * issue in practice). Tests that need multi-tick periodic behaviour can
 * call [runPending] N times.
 *
 * Methods that aren't used by the production callers (`submit`,
 * `invokeAll`, `invokeAny`, `Callable`-flavoured `schedule`) deliberately
 * throw [UnsupportedOperationException] — if a future production change
 * starts using them, the test will fail loudly rather than silently
 * no-op.
 */
class DeterministicScheduler : ScheduledExecutorService {

    private val pending = mutableListOf<PendingTask>()
    private var shutdown = false

    /**
     * Runs every still-live pending task once on the calling thread, in
     * the order they were scheduled. Tasks added during the run (e.g. a
     * task that schedules another task) wait for the next [runPending]
     * call so a self-rearming task doesn't infinite-loop the test.
     */
    fun runPending() {
        val snapshot = pending.toList()
        pending.clear()
        for (task in snapshot) {
            if (task.isLive()) task.command.run()
        }
    }

    private inner class PendingTask(val command: Runnable) : ScheduledFuture<Any?> {
        private val cancelled = AtomicBoolean(false)
        fun isLive() = !cancelled.get()

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancelled.set(true)
            pending.remove(this)
            return true
        }
        override fun isCancelled() = cancelled.get()
        override fun isDone() = cancelled.get()
        override fun get(): Any? = null
        override fun get(timeout: Long, unit: TimeUnit): Any? = null
        override fun getDelay(unit: TimeUnit): Long = 0L
        override fun compareTo(other: Delayed): Int = 0
    }

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
        if (shutdown) throw RejectedExecutionException()
        val task = PendingTask(command)
        pending.add(task)
        return task
    }

    override fun scheduleAtFixedRate(
        command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit
    ): ScheduledFuture<*> {
        if (shutdown) throw RejectedExecutionException()
        val task = PendingTask(command)
        pending.add(task)
        return task
    }

    override fun scheduleWithFixedDelay(
        command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit
    ): ScheduledFuture<*> {
        if (shutdown) throw RejectedExecutionException()
        val task = PendingTask(command)
        pending.add(task)
        return task
    }

    override fun execute(command: Runnable) {
        if (shutdown) throw RejectedExecutionException()
        command.run()
    }

    override fun shutdown() { shutdown = true }

    override fun shutdownNow(): MutableList<Runnable> {
        shutdown = true
        val drained = pending.map { it.command }.toMutableList()
        pending.clear()
        return drained
    }

    override fun isShutdown(): Boolean = shutdown
    override fun isTerminated(): Boolean = shutdown
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = shutdown

    override fun <V : Any?> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> =
        throw UnsupportedOperationException("Callable schedule() is not used by production callers")

    override fun <T : Any?> submit(task: Callable<T>): Future<T> =
        throw UnsupportedOperationException("submit is not used by production callers")
    override fun <T : Any?> submit(task: Runnable, result: T): Future<T> =
        throw UnsupportedOperationException("submit is not used by production callers")
    override fun submit(task: Runnable): Future<*> =
        throw UnsupportedOperationException("submit is not used by production callers")
    override fun <T : Any?> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> =
        throw UnsupportedOperationException("invokeAll is not used by production callers")
    override fun <T : Any?> invokeAll(
        tasks: MutableCollection<out Callable<T>>, timeout: Long, unit: TimeUnit
    ): MutableList<Future<T>> =
        throw UnsupportedOperationException("invokeAll is not used by production callers")
    override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>): T =
        throw UnsupportedOperationException("invokeAny is not used by production callers")
    override fun <T : Any?> invokeAny(
        tasks: MutableCollection<out Callable<T>>, timeout: Long, unit: TimeUnit
    ): T =
        throw UnsupportedOperationException("invokeAny is not used by production callers")
}
