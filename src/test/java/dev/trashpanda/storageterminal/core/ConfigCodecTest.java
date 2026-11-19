// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConfigCodecTest {
    @Test
    void writtenFileReadsBackWithItsComments() {
        Config custom = new Config(0.5, 10, 2, 2500, 0, false, false, true, "RECENT", true);
        String text = ConfigCodec.write(custom);
        assertTrue(text.contains("// "), "file has comments");
        ConfigCodec.ReadResult result = ConfigCodec.read(text);
        assertEquals(custom, result.config());
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertEquals(Config.DEFAULTS, ConfigCodec.read(ConfigCodec.write(Config.DEFAULTS)).config());
    }

    @Test
    void missingKeysUseDefaultsSilently() {
        ConfigCodec.ReadResult result = ConfigCodec.read("{ \"maxOpensPerSecond\": 12 }");
        assertEquals(12, result.config().maxOpensPerSecond());
        assertEquals(Config.DEFAULTS.syncTimeoutMs(), result.config().syncTimeoutMs());
        assertTrue(result.problems().isEmpty());
    }

    @Test
    void badValuesFallBackAndAreReported() {
        ConfigCodec.ReadResult result = ConfigCodec.read("""
                // comment
                {
                  "reachMargin": 9,
                  "maxOpensPerSecond": "fast",
                  "abortOnDamage": "yes",
                  "defaultSort": "size",
                  "syncTimeoutMs": 5000
                }
                """);
        assertEquals(Config.DEFAULTS.reachMargin(), result.config().reachMargin());
        assertEquals(Config.DEFAULTS.maxOpensPerSecond(), result.config().maxOpensPerSecond());
        assertEquals(Config.DEFAULTS.abortOnDamage(), result.config().abortOnDamage());
        assertEquals("COUNT", result.config().defaultSort());
        assertEquals(5000, result.config().syncTimeoutMs());
        assertEquals(4, result.problems().size(), result.problems().toString());
    }

    @Test
    void brokenFileNeverThrows() {
        ConfigCodec.ReadResult result = ConfigCodec.read("{ this is not json");
        assertEquals(Config.DEFAULTS, result.config());
        assertEquals(1, result.problems().size());
        assertEquals(Config.DEFAULTS, ConfigCodec.read("[1, 2]").config());
        assertEquals(Config.DEFAULTS.withDefaultSort(SortOrder.NAME).defaultSort(), "NAME");
    }
}
