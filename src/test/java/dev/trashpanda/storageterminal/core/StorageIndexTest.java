// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class StorageIndexTest {
    private static final ItemKey DIRT = ItemKey.of("minecraft:dirt");
    private static final ItemKey STONE = ItemKey.of("minecraft:stone");
    private static final ItemKey NAMED_DIRT = new ItemKey("minecraft:dirt", "{\"minecraft:custom_name\":\"x\"}");

    private static ContainerKey key(int x) {
        return new ContainerKey("minecraft:overworld", new BlockPosition(x, 0, 0));
    }

    @Test
    void sumsAcrossContainersAndKeepsComponentsApart() {
        StorageCache cache = new StorageCache();
        cache.putSnapshot(key(1), ContainerType.CHEST, List.of(),
                List.of(new SlotStack(DIRT, 64), new SlotStack(DIRT, 10), new SlotStack(NAMED_DIRT, 1)), 1, 1);
        cache.putSnapshot(key(2), ContainerType.BARREL, List.of(),
                List.of(new SlotStack(DIRT, 26), new SlotStack(STONE, 5)), 1, 1);

        List<StorageIndex.Entry> entries = StorageIndex.aggregate(cache.records());

        assertEquals(3, entries.size());
        assertEquals(DIRT, entries.get(0).item());
        assertEquals(100, entries.get(0).total());
        assertEquals(74, entries.get(0).locations().get(0).count(), "largest location first");
        assertEquals(STONE, entries.get(1).item());
        assertEquals(NAMED_DIRT, entries.get(2).item());
    }

    @Test
    void skipsIgnoredMissingAndUnreachable() {
        StorageCache cache = new StorageCache();
        cache.putSnapshot(key(1), ContainerType.CHEST, List.of(), List.of(new SlotStack(DIRT, 1)), 1, 1);
        cache.putSnapshot(key(2), ContainerType.CHEST, List.of(), List.of(new SlotStack(DIRT, 2)), 1, 1);
        cache.putSnapshot(key(3), ContainerType.CHEST, List.of(), List.of(new SlotStack(DIRT, 4)), 1, 1);
        cache.putSnapshot(key(4), ContainerType.CHEST, List.of(), List.of(new SlotStack(DIRT, 8)), 1, 1);
        cache.toggleTag(key(2), ContainerType.CHEST, List.of(), ContainerTag.IGNORE);
        cache.setState(key(3), ContainerType.CHEST, List.of(), ContainerState.MISSING);
        cache.setState(key(4), ContainerType.CHEST, List.of(), ContainerState.UNREACHABLE);

        List<StorageIndex.Entry> entries = StorageIndex.aggregate(cache.records());

        assertEquals(1, entries.size());
        assertEquals(1, entries.get(0).total());
    }

    @Test
    void staleContentsStillCount() {
        StorageCache cache = new StorageCache();
        cache.putSnapshot(key(1), ContainerType.CHEST, List.of(), List.of(new SlotStack(DIRT, 3)), 1, 1);
        cache.markAllStale();
        assertEquals(3, StorageIndex.aggregate(cache.records()).get(0).total());
    }

    @Test
    void predictedTotalsShowAMoveBeforeItIsDone() {
        ItemKey iron = ItemKey.of("minecraft:iron_ingot");
        ItemKey gold = ItemKey.of("minecraft:gold_ingot");
        ItemKey dirt = ItemKey.of("minecraft:dirt");
        java.util.List<StorageIndex.Entry> real = java.util.List.of(
                new StorageIndex.Entry(iron, 100, java.util.List.of(), false, 1),
                new StorageIndex.Entry(gold, 10, java.util.List.of(), false, 1));
        // Taking 64 iron and all 10 gold, storing 5 dirt (new). Partway, the cache already has 70 iron left.
        java.util.List<StorageIndex.Entry> partway = java.util.List.of(
                new StorageIndex.Entry(iron, 70, java.util.List.of(), false, 1),
                new StorageIndex.Entry(gold, 10, java.util.List.of(), false, 1));
        java.util.Map<ItemKey, Long> baseline = java.util.Map.of(iron, 100L, gold, 10L, dirt, 0L);
        java.util.Map<ItemKey, Long> delta = java.util.Map.of(iron, -64L, gold, -10L, dirt, 5L);
        for (java.util.List<StorageIndex.Entry> now : java.util.List.of(real, partway)) {
            java.util.Map<ItemKey, Long> shown = new java.util.HashMap<>();
            for (StorageIndex.Entry e : StorageIndex.withPredicted(now, baseline, delta, 2)) {
                shown.put(e.item(), e.total());
            }
            assertEquals(java.util.Map.of(iron, 36L, gold, 0L, dirt, 5L), shown, "gold at 0 (stays put), dirt added, iron as it will end");
        }
        assertEquals(real, StorageIndex.withPredicted(real, java.util.Map.of(), java.util.Map.of(), 2), "no move: unchanged");
    }
}
