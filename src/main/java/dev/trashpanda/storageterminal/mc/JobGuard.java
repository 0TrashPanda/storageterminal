// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import dev.trashpanda.storageterminal.core.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

/**
 * The abort conditions shared by all jobs that open containers (SPEC section 7): disconnect,
 * dimension change, death, damage (if configured), and any screen other than the terminal.
 */
final class JobGuard {
    private static final long WAIT_FOR_SCREEN_MS = 3000L;

    private final Minecraft minecraft;
    private final Config config;
    private final long startedAtMs = System.currentTimeMillis();
    private ClientLevel level;
    private float lastHealth;
    private boolean opensStarted;

    JobGuard(Minecraft minecraft, Config config) {
        this.minecraft = minecraft;
        this.config = config;
    }

    ClientLevel level() {
        return level;
    }

    /** Call once a container has been opened; from then on any other screen aborts at once. */
    void markOpened() {
        opensStarted = true;
    }

    /** A screen that must stop the job: anything except the terminal itself. */
    boolean blockingScreenOpen() {
        return minecraft.gui.screen() != null && !(minecraft.gui.screen() instanceof TerminalScreen);
    }

    /** Why the job must stop now, or null. Call once per tick. */
    String check() {
        LocalPlayer player = minecraft.player;
        if (level == null && minecraft.level != null && player != null) {
            level = minecraft.level;
            lastHealth = player.getHealth();
        }
        if (player == null || minecraft.level == null) {
            return "disconnected";
        }
        if (minecraft.level != level) {
            return "changed dimension";
        }
        if (player.isDeadOrDying()) {
            return "died";
        }
        float health = player.getHealth();
        boolean hurt = health < lastHealth;
        lastHealth = health;
        if (hurt && config.abortOnDamage()) {
            return "took damage";
        }
        if (blockingScreenOpen()) {
            if (opensStarted) {
                return "a screen was opened";
            }
            if (System.currentTimeMillis() - startedAtMs > WAIT_FOR_SCREEN_MS) {
                return "a screen is open";
            }
        }
        return null;
    }
}
