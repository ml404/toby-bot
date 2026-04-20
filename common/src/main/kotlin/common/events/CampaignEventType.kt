package common.events

/**
 * Kinds of event that can appear in a campaign's session log.
 * The full set is declared up front so that publishers and view renderers
 * across the session-log PRs (A–D) agree on the vocabulary; PR A only emits ROLL.
 */
enum class CampaignEventType {
    ROLL,
    INITIATIVE_ROLLED,
    INITIATIVE_NEXT,
    INITIATIVE_PREV,
    INITIATIVE_CLEARED,
    PLAYER_JOINED,
    PLAYER_LEFT,
    PLAYER_KICKED,
    PLAYER_DIED,
    PLAYER_REVIVED,
    CAMPAIGN_ENDED,
    DM_NOTE,
    HIT,
    MISS,
    ATTACK_HIT,
    ATTACK_MISS,
    DAMAGE_DEALT,
    PARTICIPANT_DEFEATED
}
