package bot.toby.helpers

import common.logging.DiscordLogger
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import web.profile.ProfileCardRenderer
import web.service.ProfileCardAggregator

/**
 * Renders a member's profile card PNG.
 *
 * Three surfaces want the same picture — `/profile`, the right-click **Profile**
 * entry, and the button that crosses over from an intro list — and the render is
 * the part with a failure mode worth handling in one place rather than three.
 *
 * The aggregate-then-render pair is the same one behind the web `card.png`
 * endpoint, so a screenshot from chat and a URL render identically.
 */
@Service
class ProfileCardHelper @Autowired constructor(
    private val aggregator: ProfileCardAggregator,
    private val renderer: ProfileCardRenderer,
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    /**
     * @return the PNG bytes, or null when the card could not be built — the
     *   caller is expected to say so rather than leave a silent gap.
     *
     * The aggregate step is inside the catch as well as the render. It reaches
     * for the avatar over the network and across several services, so it is no
     * less able to throw than the drawing that follows it.
     */
    fun renderPng(guild: Guild, target: Member): ByteArray? = runCatching {
        renderer.renderPng(aggregator.build(guild, target))
    }.onFailure {
        logger.error {
            "Profile card render failed for guild ${guild.id} user ${target.id}: " +
                "${it.javaClass.simpleName}: ${it.message}"
        }
    }.getOrNull()
}
