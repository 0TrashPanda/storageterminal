// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class TakePlannerTest {
    private static final ItemKey IRON = ItemKey.of("minecraft:iron_ingot");
    private static final ItemKey DIRT = ItemKey.of("minecraft:dirt");

    private static SlotStack iron(int n) {
        return new SlotStack(IRON, n);
    }

    @Test
    void requestedAmountsPerClick() {
        assertEquals(64, TakePlanner.requested(TakePlanner.Mode.STACK, 1180, 64));
        assertEquals(18, TakePlanner.requested(TakePlanner.Mode.STACK, 18, 64));
        assertEquals(32, TakePlanner.requested(TakePlanner.Mode.HALF, 1180, 64));
        assertEquals(5, TakePlanner.requested(TakePlanner.Mode.HALF, 10, 64));
        assertEquals(4, TakePlanner.requested(TakePlanner.Mode.HALF, 7, 64), "rounded up");
        assertEquals(8, TakePlanner.requested(TakePlanner.Mode.HALF, 100, 16), "ender pearls stack to 16");
        assertEquals(1, TakePlanner.requested(TakePlanner.Mode.HALF, 1, 1));
        assertEquals(1180, TakePlanner.requested(TakePlanner.Mode.FILL, 1180, 64));
        assertEquals(1, TakePlanner.requested(TakePlanner.Mode.ONE, 1180, 64));
        assertEquals(1, TakePlanner.requested(TakePlanner.Mode.ONE, 1, 1));
        assertEquals(0, TakePlanner.amountToTake(TakePlanner.Mode.ONE, 50, 64, 0), "inventory full");
    }

    @Test
    void inventoryRoomCountsEmptyAndMatchingPartialStacks() {
        List<SlotStack> inventory = new ArrayList<>(Collections.nCopies(36, new SlotStack(DIRT, 64)));
        assertEquals(0, TakePlanner.inventoryRoom(IRON, 64, inventory));
        inventory.set(3, iron(60));
        inventory.set(7, SlotStack.EMPTY);
        assertEquals(68, TakePlanner.inventoryRoom(IRON, 64, inventory));
        assertEquals(68, TakePlanner.amountToTake(TakePlanner.Mode.FILL, 1000, 64, 68));
        assertEquals(0, TakePlanner.amountToTake(TakePlanner.Mode.STACK, 1000, 64, 0), "inventory full");
    }

    @Test
    void partialStacksFirstThenFull() {
        List<SlotStack> chest = List.of(iron(64), SlotStack.EMPTY, iron(10), new SlotStack(DIRT, 5), iron(3), iron(64));
        assertEquals(List.of(new TakePlanner.SlotTake(4, 3), new TakePlanner.SlotTake(2, 10),
                new TakePlanner.SlotTake(0, 51)), TakePlanner.slotsToTake(IRON, 64, chest, 64));
        assertEquals(List.of(new TakePlanner.SlotTake(4, 2)), TakePlanner.slotsToTake(IRON, 2, chest, 64));
        assertEquals(141, TakePlanner.slotsToTake(IRON, 1000, chest, 64).stream()
                .mapToInt(TakePlanner.SlotTake::count).sum(), "takes everything there is");
    }

    @Test
    void planVisitsNearestFirstAndSkipsUnusable() {
        StorageCache cache = new StorageCache();
        ContainerKey near = new ContainerKey("o", new BlockPosition(1, 0, 0));
        ContainerKey ignored = new ContainerKey("o", new BlockPosition(2, 0, 0));
        ContainerKey far = new ContainerKey("o", new BlockPosition(3, 0, 0));
        cache.putSnapshot(near, ContainerType.CHEST, List.of(), List.of(iron(10)), 1, 1);
        cache.putSnapshot(ignored, ContainerType.CHEST, List.of(), List.of(iron(64)), 1, 1);
        cache.putSnapshot(far, ContainerType.BARREL, List.of(), List.of(iron(64), iron(5)), 1, 1);
        cache.toggleTag(ignored, ContainerType.CHEST, List.of(), ContainerTag.IGNORE);

        List<TakePlanner.TakeOp> ops = TakePlanner.plan(IRON, 64,
                List.of(cache.get(near), cache.get(ignored), cache.get(far)), 64);

        assertEquals(3, ops.size());
        assertEquals(cache.get(near), ops.get(0).source());
        assertEquals(10, ops.get(0).count());
        assertEquals(new TakePlanner.TakeOp(cache.get(far), 1, 5), ops.get(1));
        assertEquals(new TakePlanner.TakeOp(cache.get(far), 0, 49), ops.get(2));
        assertEquals(64, TakePlanner.total(ops));
    }
}
