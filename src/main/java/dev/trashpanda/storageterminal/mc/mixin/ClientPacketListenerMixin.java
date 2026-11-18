// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc.mixin;

import dev.trashpanda.storageterminal.mc.StorageService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the service when container contents arrive and when the server closes a menu. */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    /** TAIL only runs on the client thread: the first, off-thread call returns early by exception. */
    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void storageterminal$contentSynced(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        StorageService.get().onContentSynced(packet.containerId());
    }

    /** Vanilla would also close the current screen; for our hidden menus only the menu is closed. */
    @Inject(method = "handleContainerClose", at = @At("HEAD"), cancellable = true)
    private void storageterminal$serverClosed(ClientboundContainerClosePacket packet, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.packetProcessor().isSameThread()) {
            return;
        }
        if (StorageService.get().onServerClosedMenu(minecraft, packet.getContainerId())) {
            ci.cancel();
        }
    }
}
