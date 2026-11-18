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
}
