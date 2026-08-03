package common.intro

/**
 * How many intros a user may hold per guild.
 *
 * The number was previously declared three times — `SetIntroCommand.LIMIT`,
 * `IntroWebService.MAX_INTRO_COUNT` and the `maxIntros` model attribute — with
 * nothing tying them together, so raising the cap meant remembering all three.
 */
object IntroSlots {
    const val MAX_INTRO_COUNT = 3
}
