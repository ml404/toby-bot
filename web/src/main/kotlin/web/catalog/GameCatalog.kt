package web.catalog

/**
 * Single source of truth for the games listed on the homepage. Adding,
 * removing, or renaming a game here updates every count and label
 * rendered by `home.html` (stats strip, hero, casino feature card body,
 * feature card tag) at once. Out of scope (still hardcoded): the navbar
 * Play menu, `/casino/guilds` picker, the database-layer JackpotGame
 * enum, and SetConfigStakesModal — each represents a different slice of
 * "games" with different semantics.
 *
 * Two surfaces share this catalog but mean different things by "game":
 *  - **Casino**: minigames + tables + draws. House-edge games that
 *    tribute to the shared jackpot pool. Reflected by [total],
 *    [minigames], [minigameNames] — the numbers the hero and casino
 *    feature card render.
 *  - **PvP**: head-to-head player matchups (no house, no jackpot
 *    tribute). Reflected by [pvpGames], [pvpCount], [pvpNames].
 *
 * [total] deliberately excludes PvP so the hero copy ("[N]-game casino")
 * stays accurate when PvP games are added or removed. Surfaces that
 * want to mention PvP should read [pvpCount] / [pvpNames] alongside.
 */
object GameCatalog {

    enum class Category { MINIGAME, TABLE, DRAW, PVP }

    data class Game(val displayName: String, val category: Category)

    val games: List<Game> = listOf(
        Game("slots", Category.MINIGAME),
        Game("coinflip", Category.MINIGAME),
        Game("dice", Category.MINIGAME),
        Game("high-low", Category.MINIGAME),
        Game("scratch", Category.MINIGAME),
        Game("keno", Category.MINIGAME),
        Game("roulette", Category.MINIGAME),
        Game("baccarat", Category.MINIGAME),
        Game("Casino Hold’em", Category.MINIGAME),
        Game("plinko", Category.MINIGAME),
        Game("horse racing", Category.MINIGAME),
        Game("wheel", Category.MINIGAME),
        Game("Poker", Category.TABLE),
        Game("Blackjack", Category.TABLE),
        Game("Lottery", Category.DRAW),
        Game("rock-paper-scissors", Category.PVP),
        Game("tic-tac-toe", Category.PVP),
        Game("connect 4", Category.PVP),
    )

    val total: Int = games.count { it.category != Category.PVP }

    val minigames: List<Game> = games.filter { it.category == Category.MINIGAME }

    val minigameCount: Int = minigames.size

    val minigameNames: String = minigames.joinToString(", ") { it.displayName }

    val pvpGames: List<Game> = games.filter { it.category == Category.PVP }

    val pvpCount: Int = pvpGames.size

    val pvpNames: String = pvpGames.joinToString(", ") { it.displayName }
}
