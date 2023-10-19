package toby.helpers;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import toby.jpa.service.IBrotherService;

@Component
public class AppStartupEvent implements ApplicationListener<ApplicationReadyEvent> {

    public AppStartupEvent(IBrotherService brotherService) {
        this.brotherService = brotherService;
    }

    IBrotherService brotherService;
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
    }
}
