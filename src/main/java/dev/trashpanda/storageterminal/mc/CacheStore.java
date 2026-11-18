// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import dev.trashpanda.storageterminal.StorageTerminal;
import dev.trashpanda.storageterminal.core.CacheCodec;
import dev.trashpanda.storageterminal.core.StorageCache;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * One cache file per world or server, under {@code config/storageterminal/cache/}.
 * Multiplayer is keyed by server address, singleplayer by world folder name.
 */
final class CacheStore {
    private CacheStore() {
    }

    /** File-name-safe id for the world the client is in, or null if it can't be determined. */
    static String currentWorldId(Minecraft minecraft) {
        IntegratedServer integrated = minecraft.getSingleplayerServer();
        if (integrated != null) {
            Path folder = integrated.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
            return folder == null ? null : "sp_" + sanitize(folder.toString());
        }
        ServerData server = minecraft.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return "mp_" + sanitize(server.ip);
        }
        return null;
    }

    static StorageCache load(String worldId) {
        Path file = fileFor(worldId);
        if (!Files.isRegularFile(file)) {
            return new StorageCache();
        }
        try {
            CacheCodec.ReadResult result = CacheCodec.read(Files.readString(file, StandardCharsets.UTF_8));
            if (result.skippedRecords() > 0) {
                StorageTerminal.LOGGER.warn("Skipped {} unreadable containers in {}", result.skippedRecords(), file);
            }
            StorageCache cache = result.cache();
            cache.markAllStale();
            return cache;
        } catch (CacheCodec.UnsupportedSchemaException e) {
            keepAside(file, e.getMessage());
        } catch (IOException e) {
            StorageTerminal.LOGGER.error("Could not read {}, starting with an empty cache", file, e);
        }
        return new StorageCache();
    }

    static void save(String worldId, StorageCache cache) {
        Path file = fileFor(worldId);
        String json = CacheCodec.write(cache.copyRecords());
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            cache.markClean();
        } catch (IOException e) {
            StorageTerminal.LOGGER.error("Could not save the container cache to {}", file, e);
        }
    }

    static void delete(String worldId) {
        try {
            Files.deleteIfExists(fileFor(worldId));
        } catch (IOException e) {
            StorageTerminal.LOGGER.error("Could not delete the cache file for {}", worldId, e);
        }
    }

    private static void keepAside(Path file, String why) {
        Path aside = file.resolveSibling(file.getFileName() + ".unsupported-" + System.currentTimeMillis());
        try {
            Files.move(file, aside);
            StorageTerminal.LOGGER.warn("Cache file {} not usable ({}); kept as {} and starting fresh", file, why, aside);
        } catch (IOException e) {
            StorageTerminal.LOGGER.error("Cache file {} not usable ({}) and could not be moved aside", file, why, e);
        }
    }

    private static Path fileFor(String worldId) {
        return FabricLoader.getInstance().getConfigDir()
                .resolve(StorageTerminal.MOD_ID).resolve("cache").resolve(worldId + ".json");
    }

    private static String sanitize(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return s.length() > 100 ? s.substring(0, 100) : s;
    }
}
