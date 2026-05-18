package common.notification

/**
 * Provider-agnostic push notification payload. The actual delivery
 * adapter (Firebase, web push, etc.) maps this to its wire format.
 * Kept intentionally minimal — just enough for a notification banner.
 *
 * Today no adapter is wired; `NotificationRouter.sendPush` accepts a
 * payload lambda but drops the result with a "no push adapter wired"
 * log. The future adapter PR plugs in here.
 */
data class PushPayload(
    val title: String,
    val body: String,
    /** Optional click-through URL. Provider-specific behaviour. */
    val deepLink: String? = null,
)
