// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc.mixin;

import dev.trashpanda.storageterminal.mc.StorageService;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Container open/close sounds are not played for this player while the terminal is open or a
 * job runs (the server still plays them for everyone else; this only affects what we hear).
 */
@Mixin(SoundManager.class)
abstract class SoundManagerMixin {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void storageterminal$muteContainerSounds(SoundInstance instance,
            CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (StorageService.get().shouldMute(instance.getIdentifier())) {
            cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
        }
    }
}
