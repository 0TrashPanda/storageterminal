// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

/** A block position, independent of Minecraft's BlockPos. Ordered by x, then y, then z. */
public record BlockPosition(int x, int y, int z) implements Comparable<BlockPosition> {
    @Override
    public int compareTo(BlockPosition other) {
        if (x != other.x) {
            return Integer.compare(x, other.x);
        }
        if (y != other.y) {
            return Integer.compare(y, other.y);
        }
        return Integer.compare(z, other.z);
    }

    /** The smaller of two positions; used as the canonical position of a double chest. */
    public static BlockPosition min(BlockPosition a, BlockPosition b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z;
    }
}
