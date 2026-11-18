// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class PutPlannerTest {
    private static final ItemKey IRON = ItemKey.of("minecraft:iron_ingot");
    private static final ItemKey DIRT = ItemKey.of("minecraft:dirt");

    private final StorageCache cache = new StorageCache();
    private int next;

    private ContainerRecord chest(ContainerType type, SlotStack... slots) {
        ContainerKey key = new ContainerKey("o", new BlockPosition(next++, 0, 0));
        List<SlotStack> all = new ArrayList<>(Collections.nCopies(27, new SlotStack(DIRT, 64)));
        for (int i = 0; i < slots.length; i++) {
            all.set(i, slots[i]);
        }
        cache.putSnapshot(key, type, List.of(), all, 1, 1);
        return cache.get(key);
    }

    @Test
    void partialStacksThenSameItemThenFreeSpaceNearestFirst() {
        ContainerRecord free = chest(ContainerType.CHEST, SlotStack.EMPTY);                          // nearest
        ContainerRecord sameItemFull = chest(ContainerType.CHEST, new SlotStack(IRON, 64), SlotStack.EMPTY);
        ContainerRecord full = chest(ContainerType.CHEST);                                           // no room
        ContainerRecord partial = chest(ContainerType.BARREL, new SlotStack(IRON, 60));              // farthest

        List<PutPlanner.PutStop> stops = PutPlanner.plan(IRON, 64, List.of(free, sameItemFull, full, partial), s -> true);

        assertEquals(List.of(partial, sameItemFull, free), stops.stream().map(PutPlanner.PutStop::source).toList());
        assertEquals(4, stops.get(0).room());
        assertEquals(64, stops.get(1).room());
        assertEquals(64, stops.get(2).room());
        assertEquals(132, PutPlanner.totalRoom(stops));
    }

    @Test
    void ignoredAndRejectedContainersAreNeverUsed() {
        ContainerRecord ignored = chest(ContainerType.CHEST, SlotStack.EMPTY);
        cache.toggleTag(ignored.key(), ContainerType.CHEST, List.of(), ContainerTag.IGNORE);
        ContainerRecord shulker = chest(ContainerType.SHULKER_BOX, SlotStack.EMPTY);
        ContainerRecord ok = chest(ContainerType.CHEST, SlotStack.EMPTY);

        List<PutPlanner.PutStop> stops = PutPlanner.plan(IRON, 64, List.of(ignored, shulker, ok),
                s -> ((ContainerRecord) s).type() != ContainerType.SHULKER_BOX);

        assertEquals(List.of(ok), stops.stream().map(PutPlanner.PutStop::source).toList());
    }

    @Test
    void fullStorageHasNoRoom() {
        ContainerRecord full = chest(ContainerType.CHEST);
        assertTrue(PutPlanner.plan(IRON, 64, List.of(full), s -> true).isEmpty());
        assertEquals(0, PutPlanner.totalRoom(List.of()));
    }
}
