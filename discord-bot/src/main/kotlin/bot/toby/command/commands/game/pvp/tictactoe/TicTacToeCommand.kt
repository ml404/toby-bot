package bot.toby.command.commands.game.pvp.tictactoe

import bot.toby.helpers.UserDtoHelper
import core.command.CommandContext
import database.dto.user.UserDto
import database.service.pvp.tictactoe.TicTacToeService
import database.tictactoe.TicTacToeSessionRegistry
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import bot.toby.command.commands.game.pvp.GameCommand
import bot.toby.command.commands.game.pvp.PvpChallengeCeremony
import bot.toby.command.commands.game.pvp.tictactoe.TicTacToeEmbeds

/**
 * `/tictactoe user:<member> [stake:<int>]` — challenge another user to
 * a round of Tic-Tac-Toe. Optional wager: when `stake > 0` both players
 * ante on accept and the winner takes the pot minus the per-guild
 * jackpot tribute (same model as `/rps`). When `stake` is omitted or
 * 0, the match is pure-fun and the winner only earns a small XP grant.
 *
 * Initiator plays ❌ (moves first); opponent plays ⭕.
 *
 * Accept/decline + per-cell place + forfeit clicks are handled by
 * [bot.toby.button.buttons.pvp.tictactoe.TicTacToeButton], which routes through
 * [TicTacToeSessionRegistry] (in-memory board + turn state) and
 * [TicTacToeService] (wager arithmetic).
 *
 * The handler body is the shared [PvpChallengeCeremony] — see that
 * file for the per-game ceremony shape.
 */
@Component
class TicTacToeCommand @Autowired constructor(
    private val ticTacToeService: TicTacToeService,
    private val ticTacToeSessionRegistry: TicTacToeSessionRegistry,
    private val userDtoHelper: UserDtoHelper,
) : GameCommand {

    override val name: String = "tictactoe"
    override val description: String =
        "Challenge another user to Tic-Tac-Toe. Stake is optional — leave it off for free play."

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
            startMatch = { init, opp, gid, stake -> ticTacToeService.startMatch(init, opp, gid, stake) },
            register = { gid, init, opp, stake, cb ->
                ticTacToeSessionRegistry.register(gid, init, opp, stake, onPendingTimeout = cb)
            },
            pendingEmbed = TicTacToeEmbeds::pendingEmbed,
            pendingButtons = TicTacToeEmbeds::pendingButtons,
            pendingTimeoutEmbed = TicTacToeEmbeds::pendingTimeoutEmbed,
        )
}
