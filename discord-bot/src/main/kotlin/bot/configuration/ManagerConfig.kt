package bot.configuration

import bot.toby.helpers.UserDtoHelper
import bot.toby.managers.DefaultButtonManager
import bot.toby.managers.DefaultCommandManager
import bot.toby.managers.DefaultMenuManager
import core.button.Button
import core.managers.ButtonManager
import core.managers.CommandManager
import core.managers.MenuManager
import core.menu.Menu
import database.service.ConfigService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Profile("prod")
@Configuration
open class ManagerConfig {

    @Bean
    open fun commandManager(
        configService: ConfigService,
        userDtoHelper: UserDtoHelper,
        commandList: List<core.command.Command>
    ): CommandManager {
        return DefaultCommandManager(
            configService,
            userDtoHelper,
            commandList
        )
    }

    @Bean
    open fun menuManager(
        configService: ConfigService,
        menus: List<Menu>
    ): MenuManager {
        return DefaultMenuManager(configService, menus)
    }

    @Bean
    open fun buttonManager(
        configService: ConfigService,
        userDtoHelper: UserDtoHelper,
        buttons: List<Button>
    ): ButtonManager {
        return DefaultButtonManager(configService, userDtoHelper, buttons)
    }
}