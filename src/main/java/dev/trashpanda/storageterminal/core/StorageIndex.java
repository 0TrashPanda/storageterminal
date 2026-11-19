// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Aggregates item counts across sources. */
public final class StorageIndex {
    /** Where some of an item is: which source and how many. */
    public record Location(StorageSource source, long count) {
    }

    /**
     * One line of the aggregated list. Locations are sorted by count, largest first.
     *
     * @param anyStale        true if part of the total comes from contents not rescanned this session
     * @param lastChangedAt   latest change of this item's count in any source (epoch millis, 0 = unknown)
     */
    public record Entry(ItemKey item, long total, List<Location> locations, boolean anyStale, long lastChangedAt) {
    }

    private StorageIndex() {
    }

    /** Totals over every usable source, largest total first, ties broken by item id then components. */
    public static List<Entry> aggregate(Collection<? extends StorageSource> sources) {
        Map<ItemKey, Map<StorageSource, Long>> perItem = new LinkedHashMap<>();
        for (StorageSource source : sources) {
            if (!source.isUsable()) {
                continue;
            }
            for (SlotStack slot : source.slots()) {
                if (!slot.isEmpty()) {
                    perItem.computeIfAbsent(slot.item(), k -> new LinkedHashMap<>())
                            .merge(source, (long) slot.count(), Long::sum);
                }
            }
        }
        List<Entry> entries = new ArrayList<>(perItem.size());
        for (Map.Entry<ItemKey, Map<StorageSource, Long>> item : perItem.entrySet()) {
            List<Location> locations = new ArrayList<>();
            long total = 0;
            boolean anyStale = false;
            long lastChanged = 0;
            for (Map.Entry<StorageSource, Long> loc : item.getValue().entrySet()) {
                StorageSource source = loc.getKey();
                locations.add(new Location(source, loc.getValue()));
                total += loc.getValue();
                anyStale |= source.isStale();
                lastChanged = Math.max(lastChanged, source.changedAt(item.getKey()));
            }
            locations.sort(Comparator.comparingLong(Location::count).reversed());
            entries.add(new Entry(item.getKey(), total, List.copyOf(locations), anyStale, lastChanged));
        }
        entries.sort(Comparator.comparingLong(Entry::total).reversed()
                .thenComparing(e -> e.item().itemId())
                .thenComparing(e -> e.item().components()));
        return entries;
    }

    /**
     * The list as it will be once a running move is done, so the terminal can show it at once:
     * each item in {@code delta} gets {@code baseline + delta} (baseline = its total when the move
     * started). Items that reach 0 stay as 0 until the move is done, so the grid does not shift
     * under the mouse; items new to storage are added.
     */
    public static List<Entry> withPredicted(List<Entry> real, Map<ItemKey, Long> baseline, Map<ItemKey, Long> delta,
            long now) {
        if (delta.isEmpty()) {
            return real;
        }
        List<Entry> out = new ArrayList<>(real.size() + delta.size());
        Set<ItemKey> seen = new HashSet<>();
        for (Entry entry : real) {
            Long change = delta.get(entry.item());
            if (change == null) {
                out.add(entry);
                continue;
            }
            seen.add(entry.item());
            long total = Math.max(0, baseline.getOrDefault(entry.item(), entry.total()) + change);
            out.add(new Entry(entry.item(), total, entry.locations(), entry.anyStale(), now));
        }
        for (Map.Entry<ItemKey, Long> change : delta.entrySet()) {
            long total = baseline.getOrDefault(change.getKey(), 0L) + change.getValue();
            if (!seen.contains(change.getKey()) && total > 0) {
                out.add(new Entry(change.getKey(), total, List.of(), false, now));
            }
        }
        return out;
    }
}
