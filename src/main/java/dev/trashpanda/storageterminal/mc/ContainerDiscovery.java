// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import dev.trashpanda.storageterminal.core.BlockPosition;
import dev.trashpanda.storageterminal.core.Config;
import dev.trashpanda.storageterminal.core.ContainerKey;
import dev.trashpanda.storageterminal.core.ContainerType;
import dev.trashpanda.storageterminal.core.Reach;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Finds supported containers in reach, from the client world only (loaded chunks). */
final class ContainerDiscovery {
    /** A container in reach. {@code openPos} is the half to click: the nearest one in reach. */
    record Target(ContainerBlocks.Found found, BlockPos openPos, double distanceSq) {
        ContainerKey key() {
            return found.key();
        }
    }

    private ContainerDiscovery() {
    }

    /** All supported containers in reach, nearest first. Nothing is filtered by tags or cache here. */
    static List<Target> findInReach(Level level, LocalPlayer player, Config config) {
        Vec3 eye = player.getEyePosition();
        double range = player.blockInteractionRange();
        int r = Mth.ceil(range);
        Map<ContainerKey, Target> byKey = new LinkedHashMap<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = Mth.floor(eye.x) - r; x <= Mth.floor(eye.x) + r; x++) {
            for (int y = Mth.floor(eye.y) - r; y <= Mth.floor(eye.y) + r; y++) {
                for (int z = Mth.floor(eye.z) - r; z <= Mth.floor(eye.z) + r; z++) {
                    BlockPosition core = new BlockPosition(x, y, z);
                    if (!Reach.isWithin(eye.x, eye.y, eye.z, core, range, config.reachMargin())) {
                        continue;
                    }
                    cursor.set(x, y, z);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    ContainerBlocks.Found found = ContainerBlocks.identify(level, cursor);
                    if (found == null) {
                        continue;
                    }
                    if (found.type() == ContainerType.TRAPPED_CHEST && !config.includeTrappedChests()) {
                        continue;
                    }
                    double distSq = Reach.distanceSqToBlock(eye.x, eye.y, eye.z, core);
                    Target existing = byKey.get(found.key());
                    if (existing == null || distSq < existing.distanceSq()) {
                        byKey.put(found.key(), new Target(found, cursor.immutable(), distSq));
                    }
                }
            }
        }
        List<Target> targets = new ArrayList<>(byKey.values());
        targets.sort(Comparator.comparingDouble(Target::distanceSq));
        return targets;
    }

    /** True while the given half is still in reach (server tolerance, no safety margin). */
    static boolean stillInServerReach(LocalPlayer player, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        return Reach.isWithin(eye.x, eye.y, eye.z, ContainerBlocks.toCore(pos), player.blockInteractionRange(), 0.0);
    }
}
