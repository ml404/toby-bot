package database.configuration

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

object RegistryScheduler {
    val instance: ScheduledExecutorService = Executors.newScheduledThreadPool(3, DaemonThreadFactory)

    private object DaemonThreadFactory : ThreadFactory {
        private val seq = AtomicInteger()
        override fun newThread(r: Runnable) = Thread(r, "registry-scheduler-${seq.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
}
