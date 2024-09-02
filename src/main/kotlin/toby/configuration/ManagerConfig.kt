package toby.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import toby.helpers.HttpHelper
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
        httpHelper: HttpHelper
    ): CommandManager {
        return CommandManager(configService, brotherService, userService, musicFileService, excuseService, httpHelper)
    }

    @Bean
    open fun menuManager(
        configService: IConfigService,
        httpHelper: HttpHelper
    ): MenuManager {
        return MenuManager(configService, httpHelper)
    }

    @Bean
    open fun buttonManager(
        configService: IConfigService,
        userService: IUserService,
        commandManager: CommandManager
    ): ButtonManager {
        return ButtonManager(configService, userService, commandManager)
    }
}