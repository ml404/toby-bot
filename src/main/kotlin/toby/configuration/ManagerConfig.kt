package toby.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import toby.helpers.DnDHelper
import toby.helpers.HttpHelper
import toby.helpers.IntroHelper
import toby.helpers.UserDtoHelper
import toby.jpa.service.*
import toby.managers.ButtonManager
import toby.managers.CommandManager
import toby.managers.MenuManager

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
        dndHelper: DnDHelper
    ): MenuManager {
        return MenuManager(configService, httpHelper, introHelper, userDtoHelper, dndHelper)
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