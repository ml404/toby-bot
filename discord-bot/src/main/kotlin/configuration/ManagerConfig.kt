package configuration

import IUserService
import database.service.IBrotherService
import database.service.IConfigService
import database.service.IExcuseService
import database.service.IMusicFileService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import toby.handler.EventWaiter
import toby.helpers.DnDHelper
import toby.helpers.HttpHelper
import toby.helpers.IntroHelper
import toby.helpers.UserDtoHelper
import toby.managers.ButtonManager
import toby.managers.CommandManager
import toby.managers.MenuManager

@Profile("prod")
@Configuration
open class ManagerConfig {

    @Bean
    open fun commandManager(
        configService: IConfigService,
        brotherService: IBrotherService,
        userService: IUserService,
        musicFileService: IMusicFileService,
        excuseService: IExcuseService,
        httpHelper: HttpHelper,
        userDtoHelper: UserDtoHelper,
        introHelper: IntroHelper,
        dndHelper: DnDHelper
    ): CommandManager {
        return CommandManager(
            configService,
            brotherService,
            userService,
            excuseService,
            httpHelper,
            userDtoHelper,
            introHelper,
            dndHelper
        )
    }

    @Bean
    open fun menuManager(
        configService: IConfigService,
        httpHelper: HttpHelper,
        userDtoHelper: UserDtoHelper,
        introHelper: IntroHelper,
        dndHelper: DnDHelper,
        eventWaiter: EventWaiter
    ): MenuManager {
        return MenuManager(configService, httpHelper, introHelper, userDtoHelper, dndHelper, eventWaiter)
    }

    @Bean
    open fun buttonManager(
        configService: IConfigService,
        userDtoHelper: UserDtoHelper,
        dndHelper: DnDHelper,
        commandManager: CommandManager
    ): ButtonManager {
        return ButtonManager(configService, userDtoHelper, dndHelper, commandManager)
    }
}