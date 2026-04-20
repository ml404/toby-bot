package common.events

/**
 * Fact emitted via Spring's [org.springframework.context.ApplicationEventPublisher]
 * whenever a campaign-visible action happens (roll, initiative change, join/leave,
 * DM annotation…). A single listener in the application module resolves the
 * active campaign, persists the row, and (in later slices) fans out to SSE subscribers.
 *
 * `guildId` is the Discord guild the action happened in; the listener maps this
 * to the currently active campaign. `payloadJson` is an opaque, type-specific
 * JSON blob so new event shapes don't need schema migrations.
 */
data class CampaignEventOccurred(
    val guildId: Long,
    val type: CampaignEventType,
    val actorDiscordId: Long? = null,
    val actorName: String? = null,
    val payloadJson: String = "{}",
    val refEventId: Long? = null
)
