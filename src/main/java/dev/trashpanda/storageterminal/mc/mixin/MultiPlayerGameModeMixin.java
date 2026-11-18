// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc.mixin;

import dev.trashpanda.storageterminal.mc.StorageService;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remembers which block the player right-clicked, so containers they open by hand can be cached. */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void storageterminal$rememberManualUse(LocalPlayer player, InteractionHand hand, BlockHitResult blockHit,
            CallbackInfoReturnable<InteractionResult> cir) {
        StorageService.get().onPlayerUseItemOn(blockHit);
    }
}
