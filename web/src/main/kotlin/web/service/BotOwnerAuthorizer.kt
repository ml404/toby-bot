package web.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Single source of truth for "is this Discord user a bot operator" — the
 * person(s) who run TobyBot, as opposed to a per-guild owner/super-user.
 *
 * The operator list is a global gate (not per-guild), sourced from the
 * `BOT_OWNER_IDS` env var: a comma-separated list of Discord user ids.
 * Parsed once at construction since the raw value is final.
 *
 * Fail-closed by design: an unset/blank env var yields an empty owner set,
 * so [isBotOwner] denies everyone and any operator-only surface (the
 * `/admin/installs` page, its navbar link) stays hidden. A fresh deploy
 * that forgets the env var exposes nothing rather than everything.
 *
 * Mirrors [ModerationAuthorizer]'s role (one place that owns an access
 * predicate) but for the bot-operator gate rather than the per-guild one.
 */
@Component
class BotOwnerAuthorizer(
    @param:Value($$"${bot.owner-ids:}") botOwnerIdsRaw: String,
) {
    private val ownerIds: Set<Long> =
        botOwnerIdsRaw.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()

    fun isBotOwner(discordId: Long?): Boolean = discordId != null && discordId in ownerIds

    /** True when at least one valid operator id is configured. */
    val hasAnyOwner: Boolean get() = ownerIds.isNotEmpty()
}
