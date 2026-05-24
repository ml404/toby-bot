package bot.toby.command.commands.game.pvp.rps

import bot.toby.helpers.UserDtoHelper
import core.command.CommandContext
import database.dto.UserDto
import database.rps.RpsSessionRegistry
import database.service.pvp.rps.RpsService
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import bot.toby.command.commands.game.pvp.GameCommand
import bot.toby.command.commands.game.pvp.PvpChallengeCeremony
import bot.toby.command.commands.game.pvp.rps.RpsEmbeds

/**
 * `/rps user:<member> [stake:<int>]` — challenge another user to a
 * round of Rock-Paper-Scissors. Optional wager: when `stake > 0` both
 * players ante on accept and the winner takes the pot minus the
 * per-guild jackpot tribute (same model as `/duel`). When `stake` is
 * omitted or 0, the match is pure-fun and the winner only earns a
 * small XP grant via [database.service.leveling.XpAwardService].
 *
 * The opponent's accept/decline + both players' pick clicks are
 * handled by [bot.toby.button.buttons.RpsButton], which routes through
 * [RpsSessionRegistry] (in-memory session state) and [RpsService]
 * (wager arithmetic).
 *
 * The handler body is the shared [PvpChallengeCeremony] — see that
 * file for the per-game ceremony shape.
 */
@Component
class RpsCommand @Autowired constructor(
    private val rpsService: RpsService,
    private val rpsSessionRegistry: RpsSessionRegistry,
    private val userDtoHelper: UserDtoHelper,
) : GameCommand {

    override val name: String = "rps"
    override val description: String =
        "Challenge another user to Rock-Paper-Scissors. Stake is optional — leave it off for free play."

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
            startMatch = { init, opp, gid, stake -> rpsService.startMatch(init, opp, gid, stake) },
            register = { gid, init, opp, stake, cb ->
                rpsSessionRegistry.register(gid, init, opp, stake, onPendingTimeout = cb)
            },
            pendingEmbed = RpsEmbeds::pendingEmbed,
            pendingButtons = RpsEmbeds::pendingButtons,
            pendingTimeoutEmbed = RpsEmbeds::pendingTimeoutEmbed,
        )
}
