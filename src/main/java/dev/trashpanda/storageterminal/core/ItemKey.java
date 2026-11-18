// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import java.util.Objects;

/**
 * Item identity without count: the item id plus its data components, as canonical JSON
 * (keys sorted, see {@link JsonCanon}). Two stacks with the same key stack together.
 *
 * @param itemId     e.g. {@code minecraft:iron_ingot}
 * @param components canonical JSON of the component patch, or an empty string for none
 */
public record ItemKey(String itemId, String components) {
    public ItemKey {
        Objects.requireNonNull(itemId, "itemId");
        components = components == null ? "" : components;
    }

    public static ItemKey of(String itemId) {
        return new ItemKey(itemId, "");
    }

    public boolean hasComponents() {
        return !components.isEmpty();
    }
}
