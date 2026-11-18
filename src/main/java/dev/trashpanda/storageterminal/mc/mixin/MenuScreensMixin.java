// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc.mixin;

import dev.trashpanda.storageterminal.mc.StorageService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Screen suppression: while one of our sessions waits for a container, the server's
 * open-screen packet creates the menu but no screen is shown.
 */
@Mixin(MenuScreens.class)
abstract class MenuScreensMixin {
    @Inject(method = "create", at = @At("HEAD"), cancellable = true)
    private static <T extends AbstractContainerMenu> void storageterminal$takeOurMenu(MenuType<T> type,
            Minecraft minecraft, int containerId, Component title, CallbackInfo ci) {
        if (StorageService.get().onOpenMenu(minecraft, type, containerId)) {
            ci.cancel();
        }
    }

    @Inject(method = "create", at = @At("TAIL"))
    private static <T extends AbstractContainerMenu> void storageterminal$watchManualOpen(MenuType<T> type,
            Minecraft minecraft, int containerId, Component title, CallbackInfo ci) {
        StorageService.get().afterVanillaOpenMenu(minecraft);
    }
}
