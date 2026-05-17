package web.catalog

/**
 * Single source of truth for the games listed on the homepage. Adding,
 * removing, or renaming a game here updates every count and label
 * rendered by `home.html` (stats strip, hero, casino feature card body,
 * feature card tag) at once. Out of scope (still hardcoded): the navbar
 * Play menu, `/casino/guilds` picker, the database-layer JackpotGame
 * enum, and SetConfigStakesModal — each represents a different slice of
 * "games" with different semantics.
 */
object GameCatalog {

    enum class Category { MINIGAME, TABLE, DRAW }

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
    )

    val total: Int = games.size

    val minigames: List<Game> = games.filter { it.category == Category.MINIGAME }

    val minigameCount: Int = minigames.size

    val minigameNames: String = minigames.joinToString(", ") { it.displayName }
}
