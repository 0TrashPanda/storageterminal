// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Plans where deposited items go (SPEC section 10), per item type:
 * <ol>
 *   <li>containers with a partial stack of the item that has room,</li>
 *   <li>containers that hold the item and have an empty slot,</li>
 *   <li>any container with an empty slot,</li>
 * </ol>
 * nearest first within each group. Ignored and otherwise unusable containers are never used;
 * the caller's {@code accepts} filter rules out the rest (no shulker box into a shulker box).
 */
public final class PutPlanner {
    /** A container to visit and how many of the item it can still take (from cached contents). */
    public record PutStop(StorageSource source, long room) {
    }

    private record Ranked(StorageSource source, long room, int tier, int order) {
    }

    private PutPlanner() {
    }

    public static List<PutStop> plan(ItemKey item, int maxStack, List<? extends StorageSource> nearestFirst,
            Predicate<StorageSource> accepts) {
        List<Ranked> ranked = new ArrayList<>();
        int order = 0;
        for (StorageSource source : nearestFirst) {
            order++;
            if (!source.isUsable() || !accepts.test(source)) {
                continue;
            }
            long partialRoom = 0;
            boolean holdsItem = false;
            int empty = 0;
            for (SlotStack slot : source.slots()) {
                if (slot.isEmpty()) {
                    empty++;
                } else if (slot.item().equals(item)) {
                    holdsItem = true;
                    partialRoom += Math.max(0, maxStack - slot.count());
                }
            }
            long room = partialRoom + (long) empty * maxStack;
            if (room <= 0) {
                continue;
            }
            int tier = partialRoom > 0 ? 0 : holdsItem ? 1 : 2;
            ranked.add(new Ranked(source, room, tier, order));
        }
        ranked.sort(Comparator.comparingInt(Ranked::tier).thenComparingInt(Ranked::order));
        List<PutStop> stops = new ArrayList<>(ranked.size());
        for (Ranked r : ranked) {
            stops.add(new PutStop(r.source(), r.room()));
        }
        return stops;
    }

    public static long totalRoom(List<PutStop> stops) {
        long sum = 0;
        for (PutStop stop : stops) {
            sum += stop.room();
        }
        return sum;
    }
}
