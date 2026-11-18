// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

/**
 * Reach geometry, matching the server's check: squared distance from the eye position to the
 * nearest point of the block's full 1x1x1 box (vanilla {@code Player.isWithinBlockInteractionRange}).
 */
public final class Reach {
    private Reach() {
    }

    public static double distanceSqToBlock(double eyeX, double eyeY, double eyeZ, BlockPosition pos) {
        double dx = axisGap(eyeX, pos.x());
        double dy = axisGap(eyeY, pos.y());
        double dz = axisGap(eyeZ, pos.z());
        return dx * dx + dy * dy + dz * dz;
    }

    /** True if the block is strictly closer than {@code range - margin}. */
    public static boolean isWithin(double eyeX, double eyeY, double eyeZ, BlockPosition pos, double range,
            double margin) {
        double limit = range - margin;
        return limit > 0 && distanceSqToBlock(eyeX, eyeY, eyeZ, pos) < limit * limit;
    }

    private static double axisGap(double eye, int blockMin) {
        double min = blockMin;
        double max = blockMin + 1.0;
        return Math.max(Math.max(min - eye, eye - max), 0.0);
    }
}
