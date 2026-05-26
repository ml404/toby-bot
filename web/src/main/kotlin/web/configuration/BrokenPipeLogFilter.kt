package web.configuration

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.Marker
import org.apache.logging.log4j.core.Filter
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.config.Node
import org.apache.logging.log4j.core.config.plugins.Plugin
import org.apache.logging.log4j.core.config.plugins.PluginFactory
import org.apache.logging.log4j.core.filter.AbstractFilter
import org.apache.logging.log4j.message.Message

/**
 * Log4j2 filter that drops log events whose throwable chain contains a
 * `Broken pipe` IOException. These come from SSE heartbeats landing on
 * connections Heroku's router has already torn down at its 55-second
 * idle timeout — [web.service.sse.KeyedSseRegistry.broadcast] catches
 * the failure and evicts the dead emitter, but Tomcat's
 * `ContainerBase.[Tomcat].[localhost].[/].[dispatcherServlet]` logger
 * surfaces the IOException at ERROR before our catch runs.
 *
 * Wired into the root appender via `log4j2.xml` as
 * `<BrokenPipeLogFilter/>`.
 */
@Plugin(name = "BrokenPipeLogFilter", category = Node.CATEGORY, elementType = Filter.ELEMENT_TYPE, printObject = true)
class BrokenPipeLogFilter private constructor() : AbstractFilter(Filter.Result.DENY, Filter.Result.NEUTRAL) {

    override fun filter(event: LogEvent): Filter.Result = decide(event.thrown)

    override fun filter(logger: Logger?, level: Level?, marker: Marker?, msg: Message?, t: Throwable?): Filter.Result =
        decide(t)

    override fun filter(logger: Logger?, level: Level?, marker: Marker?, msg: Any?, t: Throwable?): Filter.Result =
        decide(t)

    override fun filter(logger: Logger?, level: Level?, marker: Marker?, msg: String?, vararg params: Any?): Filter.Result =
        Filter.Result.NEUTRAL

    private fun decide(throwable: Throwable?): Filter.Result {
        var current = throwable
        while (current != null) {
            if (current.message?.contains("Broken pipe", ignoreCase = true) == true) return Filter.Result.DENY
            current = current.cause
        }
        return Filter.Result.NEUTRAL
    }

    companion object {
        @JvmStatic
        @PluginFactory
        fun createFilter(): BrokenPipeLogFilter = BrokenPipeLogFilter()
    }
}
