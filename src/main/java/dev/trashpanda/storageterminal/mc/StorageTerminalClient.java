// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import com.mojang.blaze3d.platform.InputConstants;
import dev.trashpanda.storageterminal.StorageTerminal;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Client entrypoint. The mod is client-only; there is no common/server entrypoint. */
public final class StorageTerminalClient implements ClientModInitializer {
    private static KeyMapping openTerminalKey;
    private static KeyMapping toggleIgnoreKey;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(StorageTerminal.MOD_ID, "main"));
        openTerminalKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.storageterminal.open_terminal", InputConstants.KEY_B, category));
        toggleIgnoreKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.storageterminal.toggle_ignore", InputConstants.KEY_K, category));

        ClientCommandsSetup.register();

        StorageService service = StorageService.get();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> service.onJoin(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(service::onDisconnect));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> service.onDisconnect());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openTerminalKey.consumeClick()) {
                if (client.player != null && client.gui.screen() == null) {
                    service.openTerminal(client);
                }
            }
            while (toggleIgnoreKey.consumeClick()) {
                if (client.player != null) {
                    client.player.sendOverlayMessage(Component.literal(service.toggleIgnoreAtCrosshair(client)));
                }
            }
            service.tick(client);
        });

        StorageTerminal.LOGGER.info("Storage Terminal {} loaded", StorageTerminal.version());
    }

    static KeyMapping openTerminalKey() {
        return openTerminalKey;
    }

    static KeyMapping toggleIgnoreKey() {
        return toggleIgnoreKey;
    }
}
