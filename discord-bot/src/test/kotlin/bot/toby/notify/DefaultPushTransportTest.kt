package bot.toby.notify

import nl.martijndwars.webpush.Notification
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Security
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.random.Random

/**
 * Regression coverage for the production [DefaultPushTransport].
 *
 * In production this class instantiates eagerly when Spring wires the
 * [WebPushAdapter] bean. Pre-fix, `Security.addProvider(BouncyCastleProvider())`
 * lived inside the `by lazy { … }` initialiser for [DefaultPushTransport.pushService]
 * — but `Notification(endpoint, p256dh, auth, body)` parses the
 * recipient's `p256dh` as an EC public key BEFORE `pushService` is ever
 * touched, so every first push threw `NoSuchProviderException: no such
 * provider: BC` and the lazy initialiser never ran (so BC never got
 * registered for any subsequent push either).
 *
 * The fix moved provider registration into [DefaultPushTransport]'s
 * `init` block. These tests pin it.
 *
 * [WebPushAdapterTest] uses a recording fake [PushTransport] and would
 * not have caught this — it never exercises the production transport
 * path. Hence a focused test on [DefaultPushTransport] specifically.
 */
class DefaultPushTransportTest {

    @Test
    fun `constructing DefaultPushTransport registers BouncyCastle as a JCA provider`() {
        // Don't pre-assert that BC is absent — another test or a prior
        // VM run may have already registered it. The contract this test
        // pins is "after construction, BC is available", which is the
        // post-condition the production code needs.
        DefaultPushTransport(
            publicKey = SAMPLE_VAPID_PUBLIC_KEY,
            privateKey = SAMPLE_VAPID_PRIVATE_KEY,
            subject = "mailto:ci@example.invalid",
        )

        assertNotNull(
            Security.getProvider("BC"),
            "DefaultPushTransport's init block must register BouncyCastle before any " +
                "Notification(...) parse runs. Without it the first push throws " +
                "NoSuchProviderException: no such provider: BC."
        )
    }

    @Test
    fun `Notification(endpoint, p256dh, auth, body) does not throw after transport construction`() {
        // Construct the transport so its init block registers BC.
        DefaultPushTransport(
            publicKey = SAMPLE_VAPID_PUBLIC_KEY,
            privateKey = SAMPLE_VAPID_PRIVATE_KEY,
            subject = "mailto:ci@example.invalid",
        )

        // Build a syntactically valid recipient p256dh: a fresh P-256
        // uncompressed point. SunEC handles EC keygen without needing
        // BC, but `Notification(...)` uses BC to parse the bytes back
        // into an ECPublicKey — so this is the exact line that was
        // throwing pre-fix.
        val recipientKeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val uncompressed = uncompressedP256Bytes(recipientKeyPair.public as ECPublicKey)
        val p256dh = base64Url(uncompressed)
        val auth = base64Url(Random.Default.nextBytes(16))
        val body = "{}".toByteArray()

        assertDoesNotThrow {
            // Endpoint is just a routing string at this stage — the
            // constructor parses keys, not URLs.
            Notification(
                "https://example.invalid/push/123",
                p256dh,
                auth,
                body,
            )
        }
    }

    private fun uncompressedP256Bytes(key: ECPublicKey): ByteArray {
        val x = unsignedFixedWidth(key.w.affineX.toByteArray(), 32)
        val y = unsignedFixedWidth(key.w.affineY.toByteArray(), 32)
        return byteArrayOf(0x04) + x + y
    }

    private fun unsignedFixedWidth(bytes: ByteArray, width: Int): ByteArray {
        // BigInteger.toByteArray can emit a leading sign byte; strip
        // and left-pad to the required fixed width.
        val stripped = if (bytes.size == width + 1 && bytes[0] == 0.toByte()) {
            bytes.copyOfRange(1, bytes.size)
        } else bytes
        if (stripped.size == width) return stripped
        val padded = ByteArray(width)
        System.arraycopy(stripped, 0, padded, width - stripped.size, stripped.size)
        return padded
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        // Generated once via `npx web-push generate-vapid-keys` for use
        // as a fixture. Not used in production. Both halves are
        // syntactically valid base64url-encoded P-256 keys; lazy
        // `PushService(...)` parsing would accept them, but these tests
        // never touch the lazy field, so even invalid bytes would pass
        // both assertions today. Real values keep the test useful as a
        // copy-paste reference for future contributors.
        const val SAMPLE_VAPID_PUBLIC_KEY =
            "BLcQbCgRghW0L_Nx1XIqGqfPnWp1U8eFkU-7p7-5x40CdjC4w_4G8w5_dAxkX2t1q8" +
                "kQfaFv2hMzpb-PdKqkSjA"
        const val SAMPLE_VAPID_PRIVATE_KEY =
            "tA2J4qVy7QnvJnDQGYR_yvIvOTQ-_jUUKzM9P3qYY8M"
    }
}
