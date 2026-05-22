package bot.toby.install

import database.dto.ConfigDto.Configurations

/**
 * Reads the current value of a per-guild config key, returning null when
 * the key is unset. Used throughout the install wizard to pre-populate
 * modal fields and decide toggle/gate states without each handler
 * re-implementing the lookup.
 */
typealias ConfigReader = (Configurations) -> String?
