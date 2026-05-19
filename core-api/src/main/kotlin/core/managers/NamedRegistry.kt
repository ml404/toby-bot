package core.managers

/**
 * Marker for things addressable by a unique case-insensitive name —
 * commands, buttons, modals, menus, autocomplete handlers.
 */
interface Named {
    val name: String
}

/**
 * Case-insensitive name lookup over a typed collection. Manager interfaces
 * implement this so the lookup body lives in one place; subclasses are
 * still free to override the typed `getX(search)` method to provide
 * stateful matching (e.g. colon-prefix component IDs).
 */
interface NamedRegistry<T : Named> {
    val items: List<T>
    fun findByName(name: String): T? = items.find { it.name.equals(name, true) }
}
