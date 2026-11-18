// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared constants. Pure Java, no Minecraft classes. */
public final class StorageTerminal {
    public static final String MOD_ID = "storageterminal";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private StorageTerminal() {
    }

    public static String version() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
