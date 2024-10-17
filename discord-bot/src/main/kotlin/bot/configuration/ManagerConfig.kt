package bot.configuration

import bot.database.service.IConfigService
import bot.database.service.IUserService
import bot.toby.button.IButton
import bot.toby.command.ICommand
import bot.toby.helpers.UserDtoHelper
import bot.toby.managers.ButtonManager
import bot.toby.managers.CommandManager
import bot.toby.managers.MenuManager
import bot.toby.menu.IMenu
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Profile("prod")
@Configuration
open class ManagerConfig {

    @Bean
    open fun commandManager(
        configService: IConfigService,
        userService: IUserService,
        userDtoHelper: UserDtoHelper,
        commandList: List<ICommand>
    ): CommandManager {
        return CommandManager(
            configService,
            userService,
            userDtoHelper,
            commandList
        )
    }

    @Bean
    open fun menuManager(
        configService: IConfigService,
        menus: List<IMenu>
    ): MenuManager {
        return MenuManager(configService, menus)
    }

    @Bean
    open fun buttonManager(
        configService: IConfigService,
        userDtoHelper: UserDtoHelper,
        buttons: List<IButton>
    ): ButtonManager {
        return ButtonManager(configService, userDtoHelper, buttons)
    }
}