// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CacheCodecTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void roundTripKeepsEverything() throws Exception {
        StorageCache cache = new StorageCache();
        ContainerKey chest = new ContainerKey(OVERWORLD, new BlockPosition(1, 64, -3));
        ItemKey sword = new ItemKey("minecraft:iron_sword", "{\"minecraft:damage\":12}");
        cache.putSnapshot(chest, ContainerType.CHEST,
                List.of(new BlockPosition(1, 64, -3), new BlockPosition(2, 64, -3)),
                List.of(new SlotStack(ItemKey.of("minecraft:dirt"), 64), SlotStack.EMPTY, new SlotStack(sword, 1)),
                1_700_000_000_000L, 12345L);
        cache.toggleTag(chest, ContainerType.CHEST, List.of(), ContainerTag.IGNORE);
        ContainerKey barrel = new ContainerKey("minecraft:the_nether", new BlockPosition(0, 0, 0));
        cache.setState(barrel, ContainerType.BARREL, List.of(new BlockPosition(0, 0, 0)), ContainerState.UNREACHABLE);

        String json = CacheCodec.write(cache.copyRecords());
        CacheCodec.ReadResult result = CacheCodec.read(json);

        assertEquals(0, result.skippedRecords());
        assertEquals(2, result.cache().size());
        ContainerRecord read = result.cache().get(chest);
        assertEquals(ContainerType.CHEST, read.type());
        assertEquals(2, read.positions().size());
        assertEquals(3, read.slots().size());
        assertEquals(64, read.slots().get(0).count());
        assertTrue(read.slots().get(1).isEmpty());
        assertEquals(sword, read.slots().get(2).item());
        assertEquals(12345L, read.refreshedAtGameTime());
        assertTrue(read.isIgnored());
        assertEquals(ContainerState.UNREACHABLE, result.cache().get(barrel).state());
        assertFalse(result.cache().isDirty());
    }

    @Test
    void unknownSchemaVersionIsRejected() {
        assertThrows(CacheCodec.UnsupportedSchemaException.class,
                () -> CacheCodec.read("{\"schemaVersion\":99,\"containers\":[]}"));
        assertThrows(CacheCodec.UnsupportedSchemaException.class, () -> CacheCodec.read("not json at all {"));
    }

    @Test
    void brokenRecordIsSkippedNotFatal() throws Exception {
        String json = "{\"schemaVersion\":1,\"containers\":[{\"dimension\":\"x\"},"
                + "{\"dimension\":\"minecraft:overworld\",\"pos\":[0,0,0],\"type\":\"BARREL\",\"positions\":[[0,0,0]],"
                + "\"refreshedAtMillis\":5,\"refreshedAtGameTime\":6,\"tags\":[\"FUTURE_TAG\"],\"state\":\"STALE\","
                + "\"slots\":[null]}]}";
        CacheCodec.ReadResult result = CacheCodec.read(json);
        assertEquals(1, result.skippedRecords());
        assertEquals(1, result.cache().size());
        ContainerRecord record = result.cache().records().iterator().next();
        assertTrue(record.tags().contains("FUTURE_TAG"), "unknown tags are kept");
    }
}
