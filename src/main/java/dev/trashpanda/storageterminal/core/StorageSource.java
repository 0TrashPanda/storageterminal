// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import java.util.List;

/**
 * Something that holds items the terminal can see. A placed container is one kind; carried
 * shulker boxes, bundles and machines are later kinds (roadmap), so the index and planner
 * only talk to this interface.
 */
public interface StorageSource {
    /** Stable, human-readable id, unique within one cache. */
    String sourceId();

    List<SlotStack> slots();

    /** False when the source must not be used (ignored, missing, unreachable). */
    boolean isUsable();

    /** True if the contents come from an earlier session and have not been rescanned yet. */
    default boolean isStale() {
        return false;
    }

    /** When the count of {@code item} in this source last changed (epoch millis), or 0 if unknown. */
    default long changedAt(ItemKey item) {
        return 0L;
    }
}
