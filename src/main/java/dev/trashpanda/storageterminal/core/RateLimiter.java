// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import java.util.ArrayDeque;
import java.util.Deque;

/** Limits container opens: a minimum gap in ticks and a hard cap per rolling second. */
public final class RateLimiter {
    private final int cooldownTicks;
    private final int maxPerSecond;
    private final Deque<Long> recentOpensMillis = new ArrayDeque<>();
    private long lastOpenTick = Long.MIN_VALUE / 2;

    public RateLimiter(int cooldownTicks, int maxPerSecond) {
        this.cooldownTicks = Math.max(0, cooldownTicks);
        this.maxPerSecond = Math.max(1, maxPerSecond);
    }

    public boolean canOpen(long tick, long nowMillis) {
        dropOlderThanOneSecond(nowMillis);
        return tick - lastOpenTick >= cooldownTicks && recentOpensMillis.size() < maxPerSecond;
    }

    public void recordOpen(long tick, long nowMillis) {
        lastOpenTick = tick;
        recentOpensMillis.addLast(nowMillis);
    }

    private void dropOlderThanOneSecond(long nowMillis) {
        while (!recentOpensMillis.isEmpty() && nowMillis - recentOpensMillis.peekFirst() >= 1000L) {
            recentOpensMillis.removeFirst();
        }
    }
}
