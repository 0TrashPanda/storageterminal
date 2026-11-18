// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import java.util.Objects;

/** Identifies one container: dimension id plus its canonical position (the smaller half for a double chest). */
public record ContainerKey(String dimension, BlockPosition pos) {
    public ContainerKey {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(pos, "pos");
    }

    @Override
    public String toString() {
        return dimension + " @ " + pos;
    }
}
