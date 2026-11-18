// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import dev.trashpanda.storageterminal.StorageTerminal;
import net.fabricmc.api.ClientModInitializer;

/** Client entrypoint. The mod is client-only; there is no common/server entrypoint. */
public final class StorageTerminalClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientCommandsSetup.register();
        StorageTerminal.LOGGER.info("Storage Terminal {} loaded", StorageTerminal.version());
    }
}
