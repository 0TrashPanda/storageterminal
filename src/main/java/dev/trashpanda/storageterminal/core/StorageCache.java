// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All containers known for one world or server. Not thread-safe; used from the client thread only. */
public final class StorageCache {
    private final Map<ContainerKey, ContainerRecord> records = new LinkedHashMap<>();
    private boolean dirty;
    private int version;

    public ContainerRecord get(ContainerKey key) {
        return records.get(key);
    }

    public Collection<ContainerRecord> records() {
        return Collections.unmodifiableCollection(records.values());
    }

    public int size() {
        return records.size();
    }

    /** Stores fresh contents for a container, creating the record if needed. Tags are kept. */
    public ContainerRecord putSnapshot(ContainerKey key, ContainerType type, List<BlockPosition> positions,
            List<SlotStack> slots, long nowMillis, long gameTime) {
        ContainerRecord record = records.computeIfAbsent(key, k -> ContainerRecord.unseen(k, type, positions));
        record.applySnapshot(type, positions, slots, nowMillis, gameTime);
        touch();
        return record;
    }

    /** Changes a container's state. Unknown keys get an empty record so the state is remembered. */
    public ContainerRecord setState(ContainerKey key, ContainerType type, List<BlockPosition> positions,
            ContainerState state) {
        ContainerRecord record = records.computeIfAbsent(key, k -> ContainerRecord.unseen(k, type, positions));
        if (record.state() != state) {
            record.setState(state);
            touch();
        }
        return record;
    }

    /** Toggles a tag, creating the record if needed. Returns true if the tag is now set. */
    public boolean toggleTag(ContainerKey key, ContainerType type, List<BlockPosition> positions, ContainerTag tag) {
        ContainerRecord record = records.computeIfAbsent(key, k -> ContainerRecord.unseen(k, type, positions));
        touch();
        return record.toggleTag(tag);
    }

    /** Marks every FRESH record STALE; called after loading from disk. */
    public void markAllStale() {
        for (ContainerRecord record : records.values()) {
            if (record.state() == ContainerState.FRESH) {
                record.setState(ContainerState.STALE);
            }
        }
        version++;
    }

    public void clear() {
        records.clear();
        touch();
    }

    /** Adds a record as is; used when reading from disk. */
    void putRecord(ContainerRecord record) {
        records.put(record.key(), record);
    }

    /** Increases on every change; lets the UI notice updates cheaply. */
    public int version() {
        return version;
    }

    private void touch() {
        dirty = true;
        version++;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    /** Copies of all records, so the cache can be written to disk off the client thread. */
    public List<ContainerRecord> copyRecords() {
        List<ContainerRecord> copies = new ArrayList<>(records.size());
        for (ContainerRecord record : records.values()) {
            copies.add(record.copy());
        }
        return copies;
    }
}
