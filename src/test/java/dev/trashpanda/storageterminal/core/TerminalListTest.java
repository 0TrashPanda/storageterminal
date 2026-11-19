// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerminalListTest {
    private static final ItemKey IRON = ItemKey.of("minecraft:iron_ingot");
    private static final ItemKey OAK = ItemKey.of("minecraft:oak_log");
    private static final ItemKey GEAR = ItemKey.of("othermod:copper_gear");

    private static final TerminalList.ItemInfo INFO = new TerminalList.ItemInfo() {
        private final Map<ItemKey, String> names = Map.of(IRON, "Iron Ingot", OAK, "Oak Log", GEAR, "Copper Gear");
        private final Map<ItemKey, List<String>> tags = Map.of(
                IRON, List.of("c:ingots", "minecraft:beacon_payment_items"),
                OAK, List.of("minecraft:logs"),
                GEAR, List.of("c:gears"));

        @Override
        public String displayName(ItemKey item) {
            return names.get(item);
        }

        @Override
        public Collection<String> tags(ItemKey item) {
            return tags.get(item);
        }
    };

    private static List<StorageIndex.Entry> entries() {
        return List.of(
                new StorageIndex.Entry(IRON, 1180, List.of(), false, 100),
                new StorageIndex.Entry(OAK, 2304, List.of(), true, 300),
                new StorageIndex.Entry(GEAR, 12, List.of(), false, 200));
    }

    private static List<ItemKey> run(String query, SortOrder sort) {
        return TerminalList.filterAndSort(entries(), query, sort, INFO).stream().map(StorageIndex.Entry::item).toList();
    }

    @Test
    void sortOrders() {
        assertEquals(List.of(OAK, IRON, GEAR), run("", SortOrder.COUNT));
        assertEquals(List.of(GEAR, IRON, OAK), run("", SortOrder.NAME));
        assertEquals(List.of(IRON, OAK, GEAR), run("", SortOrder.MOD));
        assertEquals(List.of(OAK, GEAR, IRON), run("", SortOrder.RECENT));
        assertEquals(SortOrder.COUNT, SortOrder.RECENT.next());
        assertEquals(SortOrder.NAME, SortOrder.parse("name"));
        assertEquals(SortOrder.COUNT, SortOrder.parse("bogus"));
    }

    @Test
    void searchByNameModAndTag() {
        assertEquals(List.of(IRON), run("IRON", SortOrder.COUNT));
        assertEquals(List.of(GEAR), run("@othermod", SortOrder.COUNT));
        assertEquals(List.of(OAK, IRON), run("@minecraft", SortOrder.COUNT));
        assertEquals(List.of(IRON), run("#ingot", SortOrder.COUNT));
        assertEquals(List.of(OAK), run("#logs", SortOrder.COUNT));
        assertEquals(List.of(GEAR), run("copper #gears", SortOrder.COUNT), "all words must match");
        assertEquals(List.of(), run("copper #logs", SortOrder.COUNT));
        assertEquals(List.of(), run("diamon sword", SortOrder.COUNT));
    }

    @Test
    void compactCounts() {
        assertEquals("0", CountFormat.compact(0));
        assertEquals("999", CountFormat.compact(999));
        assertEquals("1k", CountFormat.compact(1000));
        assertEquals("1.1k", CountFormat.compact(1180));
        assertEquals("9.9k", CountFormat.compact(9999));
        assertEquals("18k", CountFormat.compact(18432));
        assertEquals("999k", CountFormat.compact(999_999));
        assertEquals("1.2M", CountFormat.compact(1_234_567));
        assertEquals("12M", CountFormat.compact(12_345_678));
        assertEquals("1,180", CountFormat.exact(1180));
    }

    @Test
    void changeTimesFollowCountChanges() {
        StorageCache cache = new StorageCache();
        ContainerKey key = new ContainerKey("minecraft:overworld", new BlockPosition(0, 0, 0));
        cache.putSnapshot(key, ContainerType.CHEST, List.of(), List.of(new SlotStack(IRON, 5), new SlotStack(OAK, 1)), 1000, 1);
        cache.putSnapshot(key, ContainerType.CHEST, List.of(), List.of(new SlotStack(IRON, 5), new SlotStack(OAK, 2)), 2000, 2);
        ContainerRecord record = cache.get(key);
        assertEquals(1000, record.changedAt(IRON), "unchanged count keeps its time");
        assertEquals(2000, record.changedAt(OAK));

        cache.markAllStale();
        List<StorageIndex.Entry> index = StorageIndex.aggregate(cache.records());
        assertTrue(index.get(0).anyStale());
        assertEquals(2000, index.stream().filter(e -> e.item().equals(OAK)).findFirst().orElseThrow().lastChangedAt());

        cache.putSnapshot(key, ContainerType.CHEST, List.of(), List.of(new SlotStack(IRON, 5)), 3000, 3);
        assertFalse(record.isStale());
        assertEquals(0, record.changedAt(OAK), "removed items are forgotten");
    }

    @Test
    void keepOrderHoldsPlacesWhileAMoveRuns() {
        ItemKey a = ItemKey.of("minecraft:a");
        ItemKey b = ItemKey.of("minecraft:b");
        ItemKey c = ItemKey.of("minecraft:c");
        List<StorageIndex.Entry> before = List.of(
                new StorageIndex.Entry(a, 50, List.of(), false, 0),
                new StorageIndex.Entry(b, 40, List.of(), false, 0));
        // b grew past a and c is new: a stays first, b second, c at the end.
        List<StorageIndex.Entry> fresh = List.of(
                new StorageIndex.Entry(c, 99, List.of(), false, 0),
                new StorageIndex.Entry(b, 90, List.of(), false, 0),
                new StorageIndex.Entry(a, 0, List.of(), false, 0));
        List<StorageIndex.Entry> kept = TerminalList.keepOrder(fresh, before);
        assertEquals(List.of(a, b, c), kept.stream().map(StorageIndex.Entry::item).toList());
        assertEquals(90, kept.get(1).total(), "totals are the fresh ones");
    }
}
