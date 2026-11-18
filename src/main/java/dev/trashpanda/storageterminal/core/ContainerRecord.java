// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Everything cached about one container. Mutable; owned by {@link StorageCache}. */
public final class ContainerRecord implements StorageSource {
    private final ContainerKey key;
    private ContainerType type;
    private List<BlockPosition> positions;
    private List<SlotStack> slots;
    private long refreshedAtMillis;
    private long refreshedAtGameTime;
    private final Set<String> tags;
    private ContainerState state;
    private final Map<ItemKey, Long> changedAt;

    public ContainerRecord(ContainerKey key, ContainerType type, List<BlockPosition> positions, List<SlotStack> slots,
            long refreshedAtMillis, long refreshedAtGameTime, Set<String> tags, ContainerState state,
            Map<ItemKey, Long> changedAt) {
        this.key = Objects.requireNonNull(key, "key");
        this.type = Objects.requireNonNull(type, "type");
        this.positions = List.copyOf(positions);
        this.slots = List.copyOf(slots);
        this.refreshedAtMillis = refreshedAtMillis;
        this.refreshedAtGameTime = refreshedAtGameTime;
        this.tags = new LinkedHashSet<>(tags);
        this.state = Objects.requireNonNull(state, "state");
        this.changedAt = new LinkedHashMap<>(changedAt);
    }

    /** A container we know exists but have never looked into. */
    public static ContainerRecord unseen(ContainerKey key, ContainerType type, List<BlockPosition> positions) {
        return new ContainerRecord(key, type, positions, List.of(), 0L, 0L, Set.of(), ContainerState.STALE, Map.of());
    }

    public ContainerKey key() {
        return key;
    }

    public ContainerType type() {
        return type;
    }

    public List<BlockPosition> positions() {
        return positions;
    }

    @Override
    public List<SlotStack> slots() {
        return slots;
    }

    public long refreshedAtMillis() {
        return refreshedAtMillis;
    }

    public long refreshedAtGameTime() {
        return refreshedAtGameTime;
    }

    public Set<String> tags() {
        return Collections.unmodifiableSet(tags);
    }

    public ContainerState state() {
        return state;
    }

    public boolean hasTag(ContainerTag tag) {
        return tags.contains(tag.id());
    }

    public boolean isIgnored() {
        return hasTag(ContainerTag.IGNORE);
    }

    /** True once the container has been looked into at least once (now or in an earlier session). */
    public boolean hasSnapshot() {
        return refreshedAtMillis > 0;
    }

    @Override
    public String sourceId() {
        return key.toString();
    }

    @Override
    public boolean isStale() {
        return state == ContainerState.STALE && hasSnapshot();
    }

    @Override
    public long changedAt(ItemKey item) {
        return changedAt.getOrDefault(item, 0L);
    }

    /** Per item: when its count in this container last changed. */
    public Map<ItemKey, Long> changedAtMap() {
        return Collections.unmodifiableMap(changedAt);
    }

    @Override
    public boolean isUsable() {
        return !isIgnored() && (state == ContainerState.FRESH || state == ContainerState.STALE);
    }

    /** Total item count across all slots. */
    public long totalItems() {
        long total = 0;
        for (SlotStack slot : slots) {
            total += slot.count();
        }
        return total;
    }

    void applySnapshot(ContainerType type, List<BlockPosition> positions, List<SlotStack> slots,
            long nowMillis, long gameTime) {
        Map<ItemKey, Long> before = countByItem(this.slots);
        Map<ItemKey, Long> after = countByItem(slots);
        for (Map.Entry<ItemKey, Long> e : after.entrySet()) {
            if (!e.getValue().equals(before.get(e.getKey()))) {
                changedAt.put(e.getKey(), nowMillis);
            }
        }
        changedAt.keySet().retainAll(after.keySet());
        this.type = type;
        this.positions = List.copyOf(positions);
        this.slots = List.copyOf(slots);
        this.refreshedAtMillis = nowMillis;
        this.refreshedAtGameTime = gameTime;
        this.state = ContainerState.FRESH;
    }

    void setState(ContainerState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    boolean toggleTag(ContainerTag tag) {
        if (tags.remove(tag.id())) {
            return false;
        }
        tags.add(tag.id());
        return true;
    }

    ContainerRecord copy() {
        return new ContainerRecord(key, type, positions, slots, refreshedAtMillis, refreshedAtGameTime, tags, state,
                changedAt);
    }

    private static Map<ItemKey, Long> countByItem(List<SlotStack> slots) {
        Map<ItemKey, Long> counts = new HashMap<>();
        for (SlotStack slot : slots) {
            if (!slot.isEmpty()) {
                counts.merge(slot.item(), (long) slot.count(), Long::sum);
            }
        }
        return counts;
    }
}
