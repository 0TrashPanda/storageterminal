// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Search and sort for the terminal grid (SPEC section 8). Plain words match the display name,
 * {@code @mod} matches the item's namespace, {@code #tag} matches item tags. Several words must
 * all match. Case-insensitive.
 */
public final class TerminalList {
    /** What the list needs to know about an item beyond its key; provided by the game side. */
    public interface ItemInfo {
        String displayName(ItemKey item);

        /** Item tag ids, e.g. {@code minecraft:logs}. */
        Collection<String> tags(ItemKey item);
    }

    private TerminalList() {
    }

    public static List<StorageIndex.Entry> filterAndSort(List<StorageIndex.Entry> entries, String query,
            SortOrder sort, ItemInfo info) {
        List<String> tokens = tokens(query);
        List<StorageIndex.Entry> out = new ArrayList<>(entries.size());
        for (StorageIndex.Entry entry : entries) {
            if (matchesAll(entry.item(), tokens, info)) {
                out.add(entry);
            }
        }
        out.sort(comparator(sort, info));
        return out;
    }

    static boolean matchesAll(ItemKey item, List<String> tokens, ItemInfo info) {
        for (String token : tokens) {
            if (!matches(item, token, info)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(ItemKey item, String token, ItemInfo info) {
        if (token.startsWith("@")) {
            return namespace(item).contains(token.substring(1));
        }
        if (token.startsWith("#")) {
            String wanted = token.substring(1);
            for (String tag : info.tags(item)) {
                if (tag.toLowerCase(Locale.ROOT).contains(wanted)) {
                    return true;
                }
            }
            return false;
        }
        return info.displayName(item).toLowerCase(Locale.ROOT).contains(token);
    }

    private static List<String> tokens(String query) {
        List<String> tokens = new ArrayList<>();
        if (query == null) {
            return tokens;
        }
        for (String part : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!part.isEmpty()) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private static Comparator<StorageIndex.Entry> comparator(SortOrder sort, ItemInfo info) {
        Comparator<StorageIndex.Entry> byName = Comparator.comparing(
                (StorageIndex.Entry e) -> info.displayName(e.item()).toLowerCase(Locale.ROOT))
                .thenComparing(e -> e.item().itemId())
                .thenComparing(e -> e.item().components());
        Comparator<StorageIndex.Entry> byCount = Comparator.comparingLong(StorageIndex.Entry::total).reversed();
        return switch (sort) {
            case COUNT -> byCount.thenComparing(byName);
            case NAME -> byName;
            case MOD -> Comparator.comparing((StorageIndex.Entry e) -> namespace(e.item())).thenComparing(byName);
            case RECENT -> Comparator.comparingLong(StorageIndex.Entry::lastChangedAt).reversed()
                    .thenComparing(byCount).thenComparing(byName);
        };
    }

    static String namespace(ItemKey item) {
        int colon = item.itemId().indexOf(':');
        return colon < 0 ? "minecraft" : item.itemId().substring(0, colon);
    }

    /**
     * {@code fresh} in the order of {@code previous}: entries already shown keep their place,
     * new ones go at the end, and ones that are gone stay as 0 (like Refined Storage while Shift
     * is held). Used while things move, so the grid does not jump under the mouse.
     */
    public static List<StorageIndex.Entry> keepOrder(List<StorageIndex.Entry> fresh, List<StorageIndex.Entry> previous) {
        java.util.Map<ItemKey, Integer> place = new java.util.HashMap<>();
        for (int i = 0; i < previous.size(); i++) {
            place.putIfAbsent(previous.get(i).item(), i);
        }
        List<StorageIndex.Entry> out = new java.util.ArrayList<>(fresh);
        java.util.Set<ItemKey> present = new java.util.HashSet<>();
        for (StorageIndex.Entry entry : fresh) {
            present.add(entry.item());
        }
        for (StorageIndex.Entry old : previous) {
            if (present.add(old.item())) {
                out.add(new StorageIndex.Entry(old.item(), 0, List.of(), old.anyStale(), old.lastChangedAt()));
            }
        }
        out.sort(java.util.Comparator.comparingInt(e -> place.getOrDefault(e.item(), Integer.MAX_VALUE)));
        return out;
    }
}
