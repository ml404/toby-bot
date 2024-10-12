package bot.configuration

import bot.database.service.*
import bot.toby.handler.EventWaiter
import bot.toby.helpers.DnDHelper
import bot.toby.helpers.HttpHelper
import bot.toby.helpers.IntroHelper
import bot.toby.helpers.UserDtoHelper
import bot.toby.managers.ButtonManager
import bot.toby.managers.CommandManager
import bot.toby.managers.MenuManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

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