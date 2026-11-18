// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import dev.trashpanda.storageterminal.StorageTerminal;
import dev.trashpanda.storageterminal.core.Config;
import dev.trashpanda.storageterminal.core.ConfigCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** {@code config/storageterminal/config.json}: created with defaults and comments on first start. */
final class ConfigStore {
    private ConfigStore() {
    }

    static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(StorageTerminal.MOD_ID).resolve("config.json");
    }

    /** Reads the config; bad values fall back to defaults and are logged. Never throws. */
    static Config load() {
        Path file = file();
        if (!Files.isRegularFile(file)) {
            save(Config.DEFAULTS);
            return Config.DEFAULTS;
        }
        try {
            ConfigCodec.ReadResult result = ConfigCodec.read(Files.readString(file, StandardCharsets.UTF_8));
            for (String problem : result.problems()) {
                StorageTerminal.LOGGER.warn("Config {}: {}", file, problem);
            }
            return result.config();
        } catch (IOException e) {
            StorageTerminal.LOGGER.error("Could not read {}, using defaults", file, e);
            return Config.DEFAULTS;
        }
    }

    static void save(Config config) {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, ConfigCodec.write(config), StandardCharsets.UTF_8);
        } catch (IOException e) {
            StorageTerminal.LOGGER.error("Could not write {}", file, e);
        }
    }
}
