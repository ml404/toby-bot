package bot.toby.command.commands.game.pvp.connect4

import bot.toby.helpers.UserDtoHelper
import core.command.CommandContext
import database.connect4.Connect4SessionRegistry
import database.dto.user.UserDto
import database.service.pvp.connect4.Connect4Service
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import bot.toby.command.commands.game.pvp.connect4.Connect4Embeds
import bot.toby.command.commands.game.pvp.GameCommand
import bot.toby.command.commands.game.pvp.PvpChallengeCeremony

/**
 * `/connect4 user:<member> [stake:<int>]` — challenge another user
 * to a round of Connect 4. Optional wager: when `stake > 0` both
 * players ante on accept and the winner takes the pot minus the
 * per-guild jackpot tribute (same model as `/rps` and `/tictactoe`).
 * When `stake` is omitted or 0, the match is pure-fun and the winner
 * only earns a small XP grant.
 *
 * Initiator plays 🔴 (drops first); opponent plays 🟡.
 *
 * Accept/decline + per-column drop + forfeit clicks are handled by
 * [bot.toby.button.buttons.pvp.connect4.Connect4Button], which routes through
 * [Connect4SessionRegistry] (in-memory board + turn state) and
 * [Connect4Service] (wager arithmetic).
 *
 * The handler body is the shared [PvpChallengeCeremony] — see that
 * file for the per-game ceremony shape.
 */
@Component
class Connect4Command @Autowired constructor(
    private val connect4Service: Connect4Service,
    private val connect4SessionRegistry: Connect4SessionRegistry,
    private val userDtoHelper: UserDtoHelper,
) : GameCommand {

    override val name: String = "connect4"
    override val description: String =
        "Challenge another user to Connect 4. Stake is optional — leave it off for free play."

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.USER, "user", "Opponent", true),
        OptionData(
            OptionType.INTEGER,
            "stake",
            "Credits to wager each (optional; per-guild bounds; 0 = free play)",
            false,
        ).setMinValue(0L),
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) =
        PvpChallengeCeremony.run(
            ctx = ctx,
            requestingUserDto = requestingUserDto,
            deleteDelay = deleteDelay,
            userDtoHelper = userDtoHelper,
            startMatch = { init, opp, gid, stake -> connect4Service.startMatch(init, opp, gid, stake) },
            register = { gid, init, opp, stake, cb ->
                connect4SessionRegistry.register(gid, init, opp, stake, onPendingTimeout = cb)
            },
            pendingEmbed = Connect4Embeds::pendingEmbed,
            pendingButtons = Connect4Embeds::pendingButtons,
            pendingTimeoutEmbed = Connect4Embeds::pendingTimeoutEmbed,
        )
}
