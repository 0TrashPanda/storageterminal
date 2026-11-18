// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregates item counts across sources. Search and sort for the terminal screen come in M2. */
public final class StorageIndex {
    /** Where some of an item is: which source and how many. */
    public record Location(String sourceId, long count) {
    }

    /** One line of the aggregated list. Locations are sorted by count, largest first. */
    public record Entry(ItemKey item, long total, List<Location> locations) {
    }

    private StorageIndex() {
    }

    /** Totals over every usable source, largest total first, ties broken by item id then components. */
    public static List<Entry> aggregate(Collection<? extends StorageSource> sources) {
        Map<ItemKey, Map<String, Long>> perItem = new LinkedHashMap<>();
        for (StorageSource source : sources) {
            if (!source.isUsable()) {
                continue;
            }
            for (SlotStack slot : source.slots()) {
                if (!slot.isEmpty()) {
                    perItem.computeIfAbsent(slot.item(), k -> new LinkedHashMap<>())
                            .merge(source.sourceId(), (long) slot.count(), Long::sum);
                }
            }
        }
        List<Entry> entries = new ArrayList<>(perItem.size());
        for (Map.Entry<ItemKey, Map<String, Long>> item : perItem.entrySet()) {
            List<Location> locations = new ArrayList<>();
            long total = 0;
            for (Map.Entry<String, Long> loc : item.getValue().entrySet()) {
                locations.add(new Location(loc.getKey(), loc.getValue()));
                total += loc.getValue();
            }
            locations.sort(Comparator.comparingLong(Location::count).reversed());
            entries.add(new Entry(item.getKey(), total, List.copyOf(locations)));
        }
        entries.sort(Comparator.comparingLong(Entry::total).reversed()
                .thenComparing(e -> e.item().itemId())
                .thenComparing(e -> e.item().components()));
        return entries;
    }
}
