// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

/**
 * Settings (SPEC section 13), stored in {@code config/storageterminal/config.json} by
 * {@link ConfigCodec}. Every key is explained in the file itself and in the README.
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
            false);

    public Config withDefaultSort(SortOrder sort) {
        return new Config(reachMargin, openCooldownTicks, maxOpensPerSecond, syncTimeoutMs, minRefreshIntervalMs,
                includeTrappedChests, abortOnDamage, depositIncludesHotbar, sort.name(), debugLogging);
    }
}
