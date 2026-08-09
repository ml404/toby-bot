package web.service

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext

/**
 * [CubeWebService]'s collaborators (the throttle and the HTTP transport) are
 * constructor parameters with defaults so tests can swap them, and there are
 * no beans of those types. Spring has to fall back to the Kotlin defaults —
 * if it ever doesn't, the application context fails to start, which is a
 * production outage rather than a failing feature.
 *
 * The full Spring Boot tests would catch it, but they need Docker; this
 * doesn't.
 */
class CubeWebServiceWiringTest {

    @Test
    fun `Spring can build the service with no beans for its optional collaborators`() {
        AnnotationConfigApplicationContext().use { ctx ->
            ctx.register(CubeWebService::class.java)
            ctx.refresh()

            assertNotNull(ctx.getBean(CubeWebService::class.java))
        }
    }
}
