package bot.toby.notify

import common.notification.PushPayload

/**
 * Provider-agnostic push-notification delivery contract.
 *
 * [NotificationRouter.sendPush] forwards opted-in pushes to the
 * implementation wired into the Spring context (`@Autowired(required=false)`):
 * if no bean is present, the router falls back to its one-shot
 * "no push adapter wired" warning and drops the call. The current
 * implementation [WebPushAdapter] fans out to every web-push subscription
 * the user has registered through the preferences page.
 *
 * Implementations must be best-effort: a delivery failure for one
 * subscription must not propagate to other subscriptions or to the
 * caller. The router never awaits the result.
 */
interface PushAdapter {
    /**
     * Deliver [payload] to every push surface the user has registered.
     * Implementations are responsible for looking up the user's
     * subscriptions; the router only knows the user identity and the
     * payload to send.
     */
    fun deliver(discordId: Long, payload: PushPayload)
}
