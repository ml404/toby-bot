package database.persistence.impl

import jakarta.persistence.EntityManager

/**
 * Upsert helper for the per-entity DefaultXxxPersistence classes that
 * repeat the same "persist new / merge existing, then flush" pattern.
 *
 * - `isNew` decides whether to treat the entity as not-yet-persisted.
 *   Callers typically check `id == 0L`.
 * - `onCreate` runs only on the new-entity branch, before `persist`.
 *   Use it to stamp `createdAt` and any other insert-only fields.
 *   For fields that should update on every save (e.g. `updatedAt`),
 *   set them on the entity before calling this helper.
 */
internal inline fun <T : Any> EntityManager.saveOrMerge(
    entity: T,
    isNew: (T) -> Boolean,
    onCreate: (T) -> Unit = {}
): T {
    return if (isNew(entity)) {
        onCreate(entity)
        persist(entity)
        flush()
        entity
    } else {
        @Suppress("UNCHECKED_CAST")
        val merged = merge(entity) as T
        flush()
        merged
    }
}
