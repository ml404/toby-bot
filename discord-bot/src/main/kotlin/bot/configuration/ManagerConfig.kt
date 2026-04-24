package bot.configuration

import bot.toby.helpers.UserDtoHelper
import bot.toby.managers.DefaultAutoCompleteManager
import bot.toby.managers.DefaultButtonManager
import bot.toby.managers.DefaultCommandManager
import bot.toby.managers.DefaultMenuManager
import core.autocomplete.AutocompleteHandler
import core.button.Button
import core.managers.AutocompleteManager
import core.managers.ButtonManager
import core.managers.CommandManager
import core.managers.MenuManager
import core.menu.Menu
import database.service.ConfigService
import database.service.SocialCreditAwardService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Profile("prod")
@Configuration
class ManagerConfig {

    @Bean
    fun commandManager(
        configService: ConfigService,
        userDtoHelper: UserDtoHelper,
        awardService: SocialCreditAwardService,
        commandList: List<core.command.Command>
    ): CommandManager {
        return DefaultCommandManager(
            configService,
            userDtoHelper,
            awardService,
            commandList
        )
    }

    @Bean
    fun menuManager(
        configService: ConfigService,
        menus: List<Menu>
    ): MenuManager {
        return DefaultMenuManager(configService, menus)
    }

    @Bean
    fun buttonManager(
        configService: ConfigService,
        userDtoHelper: UserDtoHelper,
        buttons: List<Button>
    ): ButtonManager {
        return DefaultButtonManager(configService, userDtoHelper, buttons)
    }

    @Bean
    fun autocompleteManager(handlers: List<AutocompleteHandler>): AutocompleteManager {
        return DefaultAutoCompleteManager(handlers)
    }
}