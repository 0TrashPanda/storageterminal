// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import com.mojang.brigadier.Command;
import dev.trashpanda.storageterminal.StorageTerminal;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.network.chat.Component;

/** Client-side commands under {@code /storageterminal}. Nothing is sent to the server. */
final class ClientCommandsSetup {
    private ClientCommandsSetup() {
    }

    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal(StorageTerminal.MOD_ID)
                        .then(ClientCommands.literal("version")
                                .executes(context -> {
                                    context.getSource().sendFeedback(
                                            Component.literal("Storage Terminal " + StorageTerminal.version()));
                                    return Command.SINGLE_SUCCESS;
                                }))));
    }
}
