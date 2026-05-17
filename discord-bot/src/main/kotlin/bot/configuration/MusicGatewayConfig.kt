package bot.configuration

import bot.toby.lavaplayer.JdaSupplier
import bot.toby.lavaplayer.SchedulerEvents
import net.dv8tion.jda.api.JDA
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MusicGatewayConfig(
    applicationEventPublisher: ApplicationEventPublisher,
) {

    init {
        // TrackScheduler is not a Spring bean (it's instantiated by
        // PlayerManager's companion singleton), so we bridge to the Spring
        // event bus via a static holder. Wiring it in the @Configuration's
        // constructor runs once at context startup, well before JDA goes
        // online — early enough that any event the scheduler publishes will
        // find a live publisher.
        SchedulerEvents.publisher = applicationEventPublisher
    }

    @Bean
    fun jdaSupplier(jdaProvider: ObjectProvider<JDA>): JdaSupplier = JdaSupplier {
        jdaProvider.ifAvailable
    }
}
