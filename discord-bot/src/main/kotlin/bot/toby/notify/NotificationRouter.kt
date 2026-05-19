package bot.toby.notify

import common.logging.DiscordLogger
import common.notification.ChannelRouteKey
import common.notification.NotificationChannelKind
import common.notification.PushPayload
import common.notification.Surface
import database.service.ConfigService
import database.service.UserNotificationPrefService
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single delivery gate for all user-targeted notifications.
 *
 * Surfaces:
 * - [sendDm] — opens a private channel and sends a Discord DM. Gated
 *   by per-user `(kind, Surface.DM)` opt-in.
 * - [sendChannel] — resolves the appropriate text channel for a
 *   [ChannelRouteKey] (config-key chain + system-channel fallback) and
 *   posts there. Channel posts are admin-gated by config; per-user
 *   `(kind, Surface.CHANNEL)` opt-in suppresses the user's `<@id>`
 *   mention via JDA's `mentionUsers(...)` allow-list when [mentions]
 *   is supplied. The post still happens; opted-out users see the
 *   content but don't get a notification.
 * - [sendPush] — provider-agnostic push notification. Forwards to the
 *   configured [PushAdapter] (today: [WebPushAdapter] when VAPID keys
 *   are present). Without an adapter bean the call drops with a one-shot
 *   "no push adapter wired" WARN once per process so a misconfigured
 *   guild doesn't flood logs.
 *
 * Callers should prefer [dispatch] over the surface primitives:
 * `dispatch(kind, discordId, guildId) { dm{…}; push{…}; channel(…){…} }`
 * fails fast at runtime when any surface the kind supports is missing
 * a builder. The bug class that motivated this — "shipped DM + channel
 * but forgot push, opted-in users got nothing" — becomes a CI failure
 * instead of a silent prod drop. The surface primitives stay public
 * for broadcast posts (e.g. [bot.toby.scheduling.LotteryAnnouncer]
 * pinging many winners in one channel message) where there's no single
 * recipient identity to key a dispatch on.
 *
 * All methods are best-effort. Dispatch failures are logged but never
 * propagate, so a synchronous caller (e.g. an event listener) never
 * stalls or throws on Discord REST hiccups.
 */
@Component
class NotificationRouter(
    @Lazy private val jda: JDA,
    private val prefService: UserNotificationPrefService,
    private val configService: ConfigService,
    @Autowired(required = false) private val pushAdapter: PushAdapter? = null,
) {
    private val logger = DiscordLogger.createLogger(this::class.java)
    private val pushAdapterMissingLogged = AtomicBoolean(false)
    private val pushAdapterFirstUseLogged = AtomicBoolean(false)

    /**
     * Send a DM to [discordId] only if they're opted in to [kind] on the
     * DM surface for [guildId]. [message] is computed lazily so we don't
     * build embed data for users who won't see it.
     */
    fun sendDm(
        discordId: Long,
        guildId: Long,
        kind: NotificationChannelKind,
        message: () -> MessageCreateData
    ) {
        if (!prefService.isOptedIn(discordId, guildId, kind, Surface.DM)) return

        val user = runCatching { jda.retrieveUserById(discordId).complete() }
            .getOrElse { err ->
                logger.warn("Could not retrieve user $discordId for $kind DM: ${err.message}")
                return
            }
        if (user == null) {
            logger.warn("retrieveUserById($discordId) returned null; dropping $kind DM.")
            return
        }

        val payload = runCatching { message() }
            .getOrElse { err ->
                logger.warn("Failed to build $kind DM payload for $discordId: ${err.message}")
                return
            }

        runCatching {
            user.openPrivateChannel().queue({ channel ->
                channel.sendMessage(payload).queue(null) { err ->
                    logger.info { "DM ($kind) to $discordId dropped: ${err.message}" }
                }
            }) { err ->
                logger.info { "openPrivateChannel for $discordId failed ($kind): ${err.message}" }
            }
        }.onFailure {
            logger.warn("NotificationRouter dispatch failed for $discordId ($kind): ${it.message}")
        }
    }

    /**
     * Post [message] to the text channel chosen for [route] in [guildId].
     * Resolution chain: `route.primaryConfigKey` → each
     * `route.fallbackConfigKeys` in order → [originChannelId] when
     * supplied → `guild.systemChannel` (if route allows). Returns
     * silently when no channel resolves.
     *
     * When [mentions] is supplied the router filters `mentions.userIds`
     * by per-user `(mentions.kind, Surface.CHANNEL)` opt-in and calls
     * `.mentionUsers(*filtered.toLongArray())` on the action — opted-out
     * users see the content but don't get notified. `EVERYONE` / `HERE`
     * allowed-mention types set by the caller's payload are preserved
     * (JDA's `mentionUsers` only restricts the USER set).
     */
    fun sendChannel(
        guildId: Long,
        route: ChannelRouteKey,
        originChannelId: Long? = null,
        message: () -> MessageCreateData,
        onSent: ((Message) -> Unit)? = null,
        mentions: ChannelMentions? = null,
    ) {
        val guild = jda.getGuildById(guildId) ?: run {
            logger.warn("sendChannel for guild $guildId but bot is not in that guild; skipping ($route).")
            return
        }
        val channel = resolveChannel(guild, route, originChannelId) ?: run {
            logger.info {
                "No writable channel resolved for route $route in guild ${guild.idLong}; skipping."
            }
            return
        }

        val payload = runCatching { message() }
            .getOrElse { err ->
                logger.warn("Failed to build $route payload for guild ${guild.idLong}: ${err.message}")
                return
            }

        runCatching {
            var action = channel.sendMessage(payload)
            if (mentions != null) {
                val whitelist = mentions.userIds
                    .filter { uid -> prefService.isOptedIn(uid, guildId, mentions.kind, Surface.CHANNEL) }
                    .toLongArray()
                action = action.mentionUsers(*whitelist)
            }
            action.queue(
                { sent ->
                    onSent?.let { cb ->
                        runCatching { cb(sent) }.onFailure {
                            logger.warn("$route onSent callback threw: ${it.message}")
                        }
                    }
                },
                { err ->
                    logger.warn("Failed to post $route to #${channel.name}: ${err.message}")
                },
            )
        }.onFailure {
            logger.warn("$route channel dispatch failed: ${it.message}")
        }
    }

    /**
     * Push-notification entry point. Routes to the configured
     * [PushAdapter] when one is registered in the Spring context;
     * otherwise the call short-circuits after the opt-in check and
     * logs "no push adapter wired" exactly once per process the first
     * time a deliverable push lands.
     *
     * [message] is built lazily — only when [kind] supports PUSH AND
     * the user is opted in. No-supported-surface returns silently
     * without logging.
     */
    fun sendPush(
        discordId: Long,
        guildId: Long,
        kind: NotificationChannelKind,
        message: () -> PushPayload,
    ) {
        if (!kind.supports(Surface.PUSH)) {
            logger.info {
                "Skip push for $kind: kind does not declare PUSH as a supported surface."
            }
            return
        }
        if (!prefService.isOptedIn(discordId, guildId, kind, Surface.PUSH)) {
            logger.info {
                "Skip push for $discordId ($kind, guild $guildId): user has not opted into PUSH."
            }
            return
        }
        val payload = runCatching { message() }
            .getOrElse { err ->
                logger.warn("Failed to build $kind push payload for $discordId: ${err.message}")
                return
            }
        val adapter = pushAdapter
        if (adapter == null) {
            if (pushAdapterMissingLogged.compareAndSet(false, true)) {
                logger.warn(
                    "Push delivery requested for kind $kind (user $discordId guild $guildId) but no " +
                        "PushAdapter is wired. Subsequent push requests will be dropped silently until " +
                        "an adapter ships."
                )
            }
            return
        }
        logger.info {
            "Delivering $kind push to $discordId in guild $guildId via ${adapter::class.simpleName}."
        }
        runCatching { adapter.deliver(discordId, payload) }
            .onSuccess {
                if (pushAdapterFirstUseLogged.compareAndSet(false, true)) {
                    logger.info {
                        "PushAdapter active: first delivery via ${adapter::class.simpleName} succeeded."
                    }
                }
            }
            .onFailure { err ->
                logger.warn("PushAdapter.deliver failed for $discordId ($kind): ${err.message}")
            }
    }

    /**
     * Single-recipient fan-out. Configure every surface [kind] supports
     * in one place — DSL throws [IllegalStateException] if a supported
     * surface is missing a builder, which makes "I forgot to wire push"
     * a CI failure instead of a silent production drop.
     *
     * For multi-recipient broadcasts (e.g. a lottery announcement
     * pinging many winners) use the [Collection]-overload below: the
     * channel surface still posts once, while DM/PUSH fan out per
     * recipient with a `(discordId) -> payload` builder.
     *
     * Surfaces the kind does **not** support stay optional: configuring
     * one is a no-op (mirrors the existing `kind.supports(surface)`
     * short-circuit in [sendDm] / [sendChannel] / [sendPush]).
     *
     * Example — wires all three surfaces for an achievement:
     * ```
     * router.dispatch(ACHIEVEMENT_UNLOCK, discordId, guildId) {
     *     dm { buildAchievementEmbed(event) }
     *     push { PushPayload(title, body, deepLink) }
     *     channel(route = ACHIEVEMENT_SHOUTOUT, mentions = …) {
     *         buildShoutoutEmbed(event)
     *     }
     * }
     * ```
     */
    fun dispatch(
        kind: NotificationChannelKind,
        discordId: Long,
        guildId: Long,
        configure: NotificationDispatch.() -> Unit,
    ) {
        val plan = NotificationDispatch(kind).apply(configure)
        enforceAllSupportedSurfacesWired(kind, plan.dmBuilder, plan.pushBuilder, plan.channelPlan)
        // Skip surfaces the kind doesn't support even if a builder was
        // wired — defends against typo-style misconfigurations where a
        // caller configures `push{}` on a DM-only kind.
        if (kind.supports(Surface.DM)) {
            plan.dmBuilder?.let { sendDm(discordId, guildId, kind, it) }
        }
        if (kind.supports(Surface.PUSH)) {
            plan.pushBuilder?.let { sendPush(discordId, guildId, kind, it) }
        }
        if (kind.supports(Surface.CHANNEL)) {
            plan.channelPlan?.let { ch ->
                sendChannel(
                    guildId = guildId,
                    route = ch.route,
                    originChannelId = ch.originChannelId,
                    message = ch.message,
                    onSent = ch.onSent,
                    mentions = ch.mentions,
                )
            }
        }
    }

    /**
     * Multi-recipient fan-out. Channel surface posts ONCE (the
     * broadcast post); DM and PUSH fan out per [discordIds] entry with
     * a `(discordId) -> payload` builder so each recipient can get a
     * personalised message. Enforcement is identical to the
     * single-recipient overload — every supported surface for [kind]
     * must be wired or dispatch throws.
     *
     * Empty [discordIds] is valid (e.g. a lottery cycle with no
     * winners): the channel broadcast still fires, DM/PUSH fan-out is
     * just a zero-iteration loop.
     *
     * Example — lottery cycle pinging every winner in one channel post
     * with a per-winner push:
     * ```
     * router.dispatch(LOTTERY_DRAW_WITH_MY_TICKET, winnerIds, guildId) {
     *     channel(route = LOTTERY, mentions = ChannelMentions(...)) {
     *         buildCycleAnnouncement()
     *     }
     *     push { winnerId -> PushPayload(...) }
     * }
     * ```
     */
    fun dispatch(
        kind: NotificationChannelKind,
        discordIds: Collection<Long>,
        guildId: Long,
        configure: MultiNotificationDispatch.() -> Unit,
    ) {
        val plan = MultiNotificationDispatch(kind).apply(configure)
        enforceAllSupportedSurfacesWired(kind, plan.dmBuilder, plan.pushBuilder, plan.channelPlan)
        // Broadcast first so per-recipient pushes don't beat the channel
        // shoutout to the user's eyeballs.
        if (kind.supports(Surface.CHANNEL)) {
            plan.channelPlan?.let { ch ->
                sendChannel(
                    guildId = guildId,
                    route = ch.route,
                    originChannelId = ch.originChannelId,
                    message = ch.message,
                    onSent = ch.onSent,
                    mentions = ch.mentions,
                )
            }
        }
        val dmBuilder = plan.dmBuilder.takeIf { kind.supports(Surface.DM) }
        val pushBuilder = plan.pushBuilder.takeIf { kind.supports(Surface.PUSH) }
        if (dmBuilder == null && pushBuilder == null) return
        discordIds.forEach { discordId ->
            dmBuilder?.let { builder ->
                sendDm(discordId, guildId, kind) { builder(discordId) }
            }
            pushBuilder?.let { builder ->
                sendPush(discordId, guildId, kind) { builder(discordId) }
            }
        }
    }

    /**
     * Shared enforcement between single- and multi-recipient dispatch.
     * Throws [IllegalStateException] with the list of supported
     * surfaces the caller forgot to wire — the failure mode that
     * shipped achievement push broken.
     */
    private fun enforceAllSupportedSurfacesWired(
        kind: NotificationChannelKind,
        dmBuilder: Any?,
        pushBuilder: Any?,
        channelPlan: Any?,
    ) {
        val missing = kind.supportedSurfaces.filter { surface ->
            when (surface) {
                Surface.DM -> dmBuilder == null
                Surface.CHANNEL -> channelPlan == null
                Surface.PUSH -> pushBuilder == null
            }
        }
        check(missing.isEmpty()) {
            "NotificationRouter.dispatch($kind) is missing builders for supported surfaces $missing. " +
                "Wire every supported surface — silent drops are how achievement push notifications " +
                "shipped broken in the first place."
        }
    }

    private fun resolveChannel(
        guild: Guild,
        route: ChannelRouteKey,
        originChannelId: Long?,
    ): TextChannel? {
        // 1. primaryConfigKey
        route.primaryConfigKey
            ?.let { key -> channelFromConfig(guild, key) }
            ?.let { return it }
        // 2. fallbackConfigKeys in order
        route.fallbackConfigKeys.forEach { key ->
            channelFromConfig(guild, key)?.let { return it }
        }
        // 3. originChannelId
        originChannelId?.let { id ->
            guild.getTextChannelById(id)?.takeIf { hasSendPerms(guild, it) }?.let { return it }
        }
        // 4. system channel fallback (per-route opt-in)
        if (route.systemChannelFallback) {
            guild.systemChannel?.takeIf { hasSendPerms(guild, it) }?.let { return it }
        }
        return null
    }

    private fun channelFromConfig(guild: Guild, configKey: String): TextChannel? {
        val raw = configService.getConfigByName(configKey, guild.id)?.value?.toLongOrNull()
            ?: return null
        val channel = guild.getTextChannelById(raw) ?: return null
        return channel.takeIf { hasSendPerms(guild, it) }
    }

    private fun hasSendPerms(guild: Guild, channel: TextChannel): Boolean =
        guild.selfMember.hasPermission(channel, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)
}

/**
 * Declares which users the caller intends to ping via `<@id>` mentions
 * inside a channel post. The router filters this list by per-user
 * `(kind, Surface.CHANNEL)` opt-in and constrains JDA's user-mention
 * whitelist accordingly — opted-out users see the post text but don't
 * get notified.
 */
data class ChannelMentions(
    val kind: NotificationChannelKind,
    val userIds: List<Long>,
)

/**
 * Builder collected by [NotificationRouter.dispatch]. One slot per
 * surface; `dispatch` enforces that every surface [kind] supports has
 * been wired before it fans out. Mutate only via the `dm` / `push` /
 * `channel` helpers — direct field access is internal to the router.
 */
class NotificationDispatch internal constructor(
    val kind: NotificationChannelKind,
) {
    internal var dmBuilder: (() -> MessageCreateData)? = null
        private set
    internal var pushBuilder: (() -> PushPayload)? = null
        private set
    internal var channelPlan: ChannelPlan? = null
        private set

    fun dm(message: () -> MessageCreateData) {
        check(dmBuilder == null) { "dm{} configured twice for $kind dispatch" }
        dmBuilder = message
    }

    fun push(message: () -> PushPayload) {
        check(pushBuilder == null) { "push{} configured twice for $kind dispatch" }
        pushBuilder = message
    }

    fun channel(
        route: ChannelRouteKey,
        originChannelId: Long? = null,
        mentions: ChannelMentions? = null,
        onSent: ((Message) -> Unit)? = null,
        message: () -> MessageCreateData,
    ) {
        check(channelPlan == null) { "channel{} configured twice for $kind dispatch" }
        channelPlan = ChannelPlan(route, originChannelId, mentions, onSent, message)
    }

    internal data class ChannelPlan(
        val route: ChannelRouteKey,
        val originChannelId: Long?,
        val mentions: ChannelMentions?,
        val onSent: ((Message) -> Unit)?,
        val message: () -> MessageCreateData,
    )
}

/**
 * Multi-recipient counterpart to [NotificationDispatch]. Channel posts
 * are broadcast (one message); DM and PUSH builders take the per-recipient
 * `discordId` so each user can get a personalised payload (e.g. a
 * "you won X credits" tailored to that winner's payout).
 *
 * Mutate only via the `dm` / `push` / `channel` helpers — direct field
 * access is internal to the router.
 */
class MultiNotificationDispatch internal constructor(
    val kind: NotificationChannelKind,
) {
    internal var dmBuilder: ((Long) -> MessageCreateData)? = null
        private set
    internal var pushBuilder: ((Long) -> PushPayload)? = null
        private set
    internal var channelPlan: NotificationDispatch.ChannelPlan? = null
        private set

    fun dm(message: (discordId: Long) -> MessageCreateData) {
        check(dmBuilder == null) { "dm{} configured twice for $kind multi-dispatch" }
        dmBuilder = message
    }

    fun push(message: (discordId: Long) -> PushPayload) {
        check(pushBuilder == null) { "push{} configured twice for $kind multi-dispatch" }
        pushBuilder = message
    }

    fun channel(
        route: ChannelRouteKey,
        originChannelId: Long? = null,
        mentions: ChannelMentions? = null,
        onSent: ((Message) -> Unit)? = null,
        message: () -> MessageCreateData,
    ) {
        check(channelPlan == null) { "channel{} configured twice for $kind multi-dispatch" }
        channelPlan = NotificationDispatch.ChannelPlan(route, originChannelId, mentions, onSent, message)
    }
}
