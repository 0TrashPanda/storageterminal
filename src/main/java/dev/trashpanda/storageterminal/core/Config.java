// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

/**
 * Settings (SPEC section 13). M1 uses the defaults only; the JSON config file with
 * comments arrives in M5.
 */
public record Config(
        double reachMargin,
        int openCooldownTicks,
        int maxOpensPerSecond,
        long syncTimeoutMs,
        long minRefreshIntervalMs,
        boolean includeTrappedChests,
        boolean abortOnDamage,
        boolean depositIncludesHotbar,
        String defaultSort,
        boolean showRememberedOutOfReach,
        boolean debugLogging) {

    public static final Config DEFAULTS = new Config(
            0.25,
            4,
            4,
            1000L,
            30_000L,
            true,
            true,
            false,
            "COUNT",
            false,
            false);
}
