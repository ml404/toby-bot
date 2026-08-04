package common.media

import common.logging.DiscordLogger
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs the `/music` URLs that serve stored intro audio.
 *
 * That endpoint has to stay anonymous: lavaplayer fetches it over plain HTTP
 * with no session when it plays a file intro, so a cookie check would break
 * playback outright. Anonymous plus a guessable key is the problem — intro ids
 * are `guildId_discordId_index` and both halves are public Discord snowflakes,
 * so anyone could walk the table and pull down every uploaded MP3 in every
 * server, unauthenticated and unmetered.
 *
 * A signature closes that without needing a session. The two callers that
 * legitimately build these URLs — the player and the intros page — both know
 * the id already, so both can mint a token; nobody else can forge one.
 *
 * ## Key
 *
 * Taken from `MEDIA_TOKEN_SECRET` when set. Without it the process generates a
 * random key at startup, which is correct and requires no configuration for a
 * single-instance deployment (the same process both mints and verifies), but
 * would reject tokens across a multi-instance one. That case logs a warning
 * rather than failing, since the current deployment is a single dyno.
 */
object MediaToken {

    const val SECRET_ENV = "MEDIA_TOKEN_SECRET"

    /**
     * How long a minted URL stays usable. Matches the endpoint's cache
     * lifetime, so a token never outlives the response it authorises. The
     * player consumes one within seconds; the intros page mints fresh ones on
     * every render, so an hour only matters to a tab left open that long.
     */
    const val TTL_SECONDS = 3_600L

    const val EXPIRY_PARAM = "exp"
    const val SIGNATURE_PARAM = "sig"

    private const val ALGORITHM = "HmacSHA256"

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    private val key: ByteArray by lazy {
        System.getenv(SECRET_ENV)?.takeIf { it.isNotBlank() }?.toByteArray()
            ?: ByteArray(32).also {
                SecureRandom().nextBytes(it)
                logger.warn {
                    "$SECRET_ENV is not set; signing intro media URLs with a per-process key. " +
                        "Fine for a single instance — set $SECRET_ENV before running more than one, " +
                        "or URLs minted by one instance will be rejected by the others."
                }
            }
    }

    /** Epoch-second deadline for a token minted at [now]. */
    fun expiryFor(now: Instant): Long = now.epochSecond + TTL_SECONDS

    fun sign(id: String, expiresAt: Long): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        // The separator keeps ("ab", 1) from colliding with ("a", 11).
        return mac.doFinal("$id|$expiresAt".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** `exp=…&sig=…`, ready to append to a `/music?id=…` URL. */
    fun queryFor(id: String, now: Instant = Instant.now()): String {
        val expiry = expiryFor(now)
        return "$EXPIRY_PARAM=$expiry&$SIGNATURE_PARAM=${sign(id, expiry)}"
    }

    /** The full relative URL the intros page and the player both request. */
    fun urlFor(id: String, now: Instant = Instant.now()): String = "/music?id=$id&${queryFor(id, now)}"

    /**
     * Whether [signature] authorises [id] at [now]. False for a missing,
     * malformed, expired or forged token — the caller shouldn't need to tell
     * those apart, and neither should the response.
     */
    fun isValid(id: String, expiresAt: Long?, signature: String?, now: Instant = Instant.now()): Boolean {
        if (expiresAt == null || signature.isNullOrBlank()) return false
        if (now.epochSecond > expiresAt) return false
        // Constant-time: a byte-at-a-time comparison would let an attacker
        // recover a valid signature one byte per round of guesses.
        return MessageDigest.isEqual(
            sign(id, expiresAt).toByteArray(),
            signature.toByteArray(),
        )
    }
}
