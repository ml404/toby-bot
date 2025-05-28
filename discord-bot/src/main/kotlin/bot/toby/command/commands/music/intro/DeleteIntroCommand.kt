package bot.toby.command.commands.music.intro

import bot.toby.command.commands.music.MusicCommand
import bot.toby.helpers.MenuHelper.DELETE_INTRO
import bot.toby.helpers.UserDtoHelper.Companion.produceMusicFileDataStringForPrinting
import bot.toby.lavaplayer.PlayerManager
import core.command.CommandContext
import database.dto.UserDto
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import org.springframework.stereotype.Component

@Component
class DeleteIntroCommand : MusicCommand {

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(
        ctx: CommandContext,
        instance: PlayerManager,
        requestingUserDto: UserDto,
        deleteDelay: Int?
    ) {
        val event = ctx.event
        event.deferReply(true).queue()

        // Fetch the user's intros
        val introList = requestingUserDto.musicDtos
        if (introList.isEmpty()) {
            event.hook.sendMessage("You have no intros to delete.").setEphemeral(true).queue()
            return
        }

        // Create the string select menu with the user's intros
        val builder = StringSelectMenu.create(DELETE_INTRO).setPlaceholder("Select an intro to delete")

        introList.forEach { intro -> builder.addOption(intro.fileName ?: "Unknown", intro.id.toString()) }

        val stringSelectMenu = builder.build()
        val introMessage = produceMusicFileDataStringForPrinting(event.member!!, requestingUserDto)

        // Send the select menu to the user
        event.hook.sendMessage("$introMessage \nPlease select an intro to delete.").addActionRow(stringSelectMenu)
            .setEphemeral(true).queue()
    }

    override val name: String
        get() = "deleteintro"

    override val description: String
        get() = "Delete one of your intros."
}