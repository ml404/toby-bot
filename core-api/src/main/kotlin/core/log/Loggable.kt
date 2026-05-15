package core.log

import common.logging.DiscordLogger

/**
 * Lazy [DiscordLogger] keyed on the implementing class. The four Discord
 * interaction interfaces (Command, Button, Menu, Modal) used to each
 * declare an identical getter — extracting the mixin keeps the
 * `DiscordLogger.createLogger(this::class.java)` call site in one place.
 */
interface Loggable {
    val logger: DiscordLogger get() = DiscordLogger.createLogger(this::class.java)
}
