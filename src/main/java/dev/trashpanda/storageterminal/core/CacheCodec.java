// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads and writes the cache file (JSON, versioned). A bad record is skipped, never fatal;
 * an unknown schema version throws {@link UnsupportedSchemaException} so the caller can
 * keep the old file aside and start fresh.
 */
public final class CacheCodec {
    public static final int SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** The file's schema version is not one this build understands. */
    public static final class UnsupportedSchemaException extends Exception {
        public UnsupportedSchemaException(String message) {
            super(message);
        }
    }

    /** Result of reading: the cache plus how many records had to be skipped. */
    public record ReadResult(StorageCache cache, int skippedRecords) {
    }

    private CacheCodec() {
    }

    public static String write(List<ContainerRecord> records) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray containers = new JsonArray();
        for (ContainerRecord record : records) {
            containers.add(writeRecord(record));
        }
        root.add("containers", containers);
        return GSON.toJson(root);
    }

    public static ReadResult read(String json) throws UnsupportedSchemaException {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new UnsupportedSchemaException("not a cache file: " + e.getMessage());
        }
        int version = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : -1;
        if (version != SCHEMA_VERSION) {
            throw new UnsupportedSchemaException("schema version " + version + ", expected " + SCHEMA_VERSION);
        }
        StorageCache cache = new StorageCache();
        int skipped = 0;
        JsonArray containers = root.has("containers") ? root.getAsJsonArray("containers") : new JsonArray();
        for (JsonElement element : containers) {
            try {
                cache.putRecord(readRecord(element.getAsJsonObject()));
            } catch (RuntimeException e) {
                skipped++;
            }
        }
        cache.markClean();
        return new ReadResult(cache, skipped);
    }

    private static JsonObject writeRecord(ContainerRecord record) {
        JsonObject o = new JsonObject();
        o.addProperty("dimension", record.key().dimension());
        o.add("pos", writePos(record.key().pos()));
        o.addProperty("type", record.type().name());
        JsonArray positions = new JsonArray();
        for (BlockPosition pos : record.positions()) {
            positions.add(writePos(pos));
        }
        o.add("positions", positions);
        o.addProperty("refreshedAtMillis", record.refreshedAtMillis());
        o.addProperty("refreshedAtGameTime", record.refreshedAtGameTime());
        JsonArray tags = new JsonArray();
        for (String tag : record.tags()) {
            tags.add(tag);
        }
        o.add("tags", tags);
        o.addProperty("state", record.state().name());
        JsonArray slots = new JsonArray();
        for (SlotStack slot : record.slots()) {
            if (slot.isEmpty()) {
                slots.add(JsonNull.INSTANCE);
            } else {
                JsonObject s = new JsonObject();
                s.addProperty("id", slot.item().itemId());
                if (slot.item().hasComponents()) {
                    s.addProperty("components", slot.item().components());
                }
                s.addProperty("count", slot.count());
                slots.add(s);
            }
        }
        o.add("slots", slots);
        JsonArray changed = new JsonArray();
        for (java.util.Map.Entry<ItemKey, Long> e : record.changedAtMap().entrySet()) {
            JsonObject c = new JsonObject();
            c.addProperty("id", e.getKey().itemId());
            if (e.getKey().hasComponents()) {
                c.addProperty("components", e.getKey().components());
            }
            c.addProperty("at", e.getValue());
            changed.add(c);
        }
        o.add("changedAt", changed);
        return o;
    }

    private static ContainerRecord readRecord(JsonObject o) {
        ContainerKey key = new ContainerKey(o.get("dimension").getAsString(), readPos(o.get("pos")));
        ContainerType type = ContainerType.valueOf(o.get("type").getAsString());
        List<BlockPosition> positions = new ArrayList<>();
        for (JsonElement p : o.getAsJsonArray("positions")) {
            positions.add(readPos(p));
        }
        Set<String> tags = new LinkedHashSet<>();
        for (JsonElement t : o.getAsJsonArray("tags")) {
            tags.add(t.getAsString());
        }
        List<SlotStack> slots = new ArrayList<>();
        for (JsonElement s : o.getAsJsonArray("slots")) {
            if (s.isJsonNull()) {
                slots.add(SlotStack.EMPTY);
            } else {
                JsonObject so = s.getAsJsonObject();
                String components = so.has("components") ? so.get("components").getAsString() : "";
                slots.add(new SlotStack(new ItemKey(so.get("id").getAsString(), components), so.get("count").getAsInt()));
            }
        }
        ContainerState state = ContainerState.valueOf(o.get("state").getAsString());
        java.util.Map<ItemKey, Long> changedAt = new java.util.LinkedHashMap<>();
        if (o.has("changedAt")) {
            for (JsonElement c : o.getAsJsonArray("changedAt")) {
                JsonObject co = c.getAsJsonObject();
                String components = co.has("components") ? co.get("components").getAsString() : "";
                changedAt.put(new ItemKey(co.get("id").getAsString(), components), co.get("at").getAsLong());
            }
        }
        return new ContainerRecord(key, type, positions, slots, o.get("refreshedAtMillis").getAsLong(),
                o.get("refreshedAtGameTime").getAsLong(), tags, state, changedAt);
    }

    private static JsonArray writePos(BlockPosition pos) {
        JsonArray a = new JsonArray();
        a.add(pos.x());
        a.add(pos.y());
        a.add(pos.z());
        return a;
    }

    private static BlockPosition readPos(JsonElement element) {
        JsonArray a = element.getAsJsonArray();
        return new BlockPosition(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }
}
