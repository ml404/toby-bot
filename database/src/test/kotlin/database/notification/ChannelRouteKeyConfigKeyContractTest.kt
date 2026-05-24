package database.notification

import common.notification.ChannelRouteKey
import database.dto.guild.ConfigDto
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * `ChannelRouteKey` lives in `:common` and carries its config-key
 * references as plain strings (so the enum doesn't pull `database.dto`
 * into the dependency graph). This test runs in `:database` — where
 * `ConfigDto.Configurations` is on the classpath — and asserts every
 * string the enum declares resolves to a real `Configurations` enum
 * value. A typo there would otherwise only surface as silent "no
 * channel resolved" at runtime; this guard makes it a CI failure.
 */
class ChannelRouteKeyConfigKeyContractTest {

    @Test
    fun `every primaryConfigKey on ChannelRouteKey resolves to a real Configurations enum value`() {
        ChannelRouteKey.entries.forEach { route ->
            val key = route.primaryConfigKey ?: return@forEach
            val resolved = runCatching { ConfigDto.Configurations.valueOf(key) }.getOrNull()
            assertNotNull(
                resolved,
                "ChannelRouteKey.${route.name}.primaryConfigKey='$key' does not match any " +
                    "ConfigDto.Configurations entry. Did you typo the config-key string?",
            )
        }
    }

    @Test
    fun `every fallbackConfigKeys entry resolves to a real Configurations enum value`() {
        ChannelRouteKey.entries.forEach { route ->
            route.fallbackConfigKeys.forEach { key ->
                val resolved = runCatching { ConfigDto.Configurations.valueOf(key) }.getOrNull()
                assertNotNull(
                    resolved,
                    "ChannelRouteKey.${route.name} fallback '$key' does not match any " +
                        "ConfigDto.Configurations entry.",
                )
            }
        }
    }
}
