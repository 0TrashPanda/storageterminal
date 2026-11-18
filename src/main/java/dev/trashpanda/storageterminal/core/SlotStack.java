// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

/** What one container slot holds. {@link #EMPTY} for an empty slot. */
public record SlotStack(ItemKey item, int count) {
    public static final SlotStack EMPTY = new SlotStack(null, 0);

    public SlotStack {
        if (item == null || count <= 0) {
            item = null;
            count = 0;
        }
    }

    public boolean isEmpty() {
        return item == null;
    }
}
