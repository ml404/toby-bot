package toby.command.commands.music.intro

import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import toby.command.CommandContext
import toby.command.commands.music.IMusicCommand
import toby.helpers.MenuHelper.EDIT_INTRO
import toby.jpa.dto.UserDto
import toby.lavaplayer.PlayerManager

class EditIntroCommand : IMusicCommand {

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
            event.hook.sendMessage("You have no intros to edit.").setEphemeral(true).queue()
            return
        }

        // Create the string select menu with the user's intros
        val builder = StringSelectMenu.create(EDIT_INTRO).setPlaceholder("Select an intro to edit")

        introList.forEach { intro -> builder.addOption(intro.fileName ?: "Unknown", intro.id.toString()) }

        val stringSelectMenu = builder.build()

        // Send the select menu to the user
        event.hook.sendMessage("Select an intro to edit:").addActionRow(stringSelectMenu).setEphemeral(true).queue()
    }

    override val name: String
        get() = "editintro"

    override val description: String
        get() = "Edit one of your intros."
}