// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import dev.trashpanda.storageterminal.core.Config;
import dev.trashpanda.storageterminal.core.ContainerRecord;
import dev.trashpanda.storageterminal.core.ContainerState;
import dev.trashpanda.storageterminal.core.Job;
import dev.trashpanda.storageterminal.core.RateLimiter;
import dev.trashpanda.storageterminal.core.StorageCache;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

/**
 * Refreshes containers in reach, nearest first, strictly one at a time. Ignored containers are
 * never opened; blocked ones are marked unreachable without trying. Aborts (closing any open
 * menu) on the conditions in SPEC section 7.
 */
final class ScanJob implements Job {
    /** Final counts, for the message to the player. */
    record Summary(int refreshed, int skippedRecent, int ignored, int unreachable, long millis, String abortReason) {
    }

    private static final long WAIT_FOR_SCREEN_MS = 3000L;

    private final Minecraft minecraft;
    private final StorageCache cache;
    private final RateLimiter rateLimiter;
    private final Config config;
    private final boolean force;
    private final Consumer<Summary> onFinished;
    private final Runnable onSessionEndedWithoutMenu;
    private final Deque<ContainerDiscovery.Target> remaining;
    private final int total;
    private final long startedAtMs = System.currentTimeMillis();
    private ClientLevel level;
    private float lastHealth;
    private boolean opensStarted;
    private ContainerSession session;
    private int refreshed;
    private int skippedRecent;
    private int ignored;
    private int unreachable;
    private boolean finished;
    private boolean waitingForSneak;

    ScanJob(Minecraft minecraft, StorageCache cache, RateLimiter rateLimiter, Config config, boolean force,
            List<ContainerDiscovery.Target> targets, Consumer<Summary> onFinished, Runnable onSessionEndedWithoutMenu) {
        this.onSessionEndedWithoutMenu = onSessionEndedWithoutMenu;
        this.minecraft = minecraft;
        this.cache = cache;
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.force = force;
        this.remaining = new ArrayDeque<>(targets);
        this.total = targets.size();
        this.onFinished = onFinished;
    }

    @Override
    public String name() {
        return "scan";
    }

    /** The session waiting for the server, if any; used by the menu hooks. */
    ContainerSession activeSession() {
        return session;
    }

    int done() {
        return total - remaining.size() - (session != null ? 1 : 0);
    }

    int total() {
        return total;
    }

    /** True while the next open waits because the player is sneaking. */
    boolean isWaitingForSneak() {
        return waitingForSneak;
    }

    /** A screen that must stop the scan: anything except the terminal itself. */
    private boolean blockingScreenOpen() {
        return minecraft.gui.screen() != null && !(minecraft.gui.screen() instanceof TerminalScreen);
    }

    @Override
    public Status tick() {
        LocalPlayer player = minecraft.player;
        if (level == null) {
            level = minecraft.level;
            lastHealth = player == null ? 0 : player.getHealth();
        }
        String abort = abortReason(player);
        if (abort != null) {
            cancel(abort);
            return Status.CANCELLED;
        }
        if (session != null) {
            if (!ContainerDiscovery.stillInServerReach(player, session.target().openPos())) {
                cancel("moved out of reach");
                return Status.CANCELLED;
            }
            session.tick(minecraft);
            switch (session.phase()) {
                case DONE -> {
                    ContainerDiscovery.Target t = session.target();
                    cache.putSnapshot(t.key(), t.found().type(), t.found().positions(), session.snapshot(),
                            System.currentTimeMillis(), level.getGameTime());
                    refreshed++;
                    session = null;
                }
                case FAILED -> {
                    markUnreachable(session.target());
                    if (session.endedWithoutMenu()) {
                        onSessionEndedWithoutMenu.run();
                    }
                    session = null;
                }
                default -> {
                    return Status.RUNNING;
                }
            }
        }
        return startNext(player);
    }

    private Status startNext(LocalPlayer player) {
        while (!remaining.isEmpty()) {
            ContainerDiscovery.Target next = remaining.peekFirst();
            ContainerRecord record = cache.get(next.key());
            if (record != null && record.isIgnored()) {
                ignored++;
                remaining.removeFirst();
                continue;
            }
            if (!force && record != null && record.state() == ContainerState.FRESH
                    && System.currentTimeMillis() - record.refreshedAtMillis() < config.minRefreshIntervalMs()) {
                skippedRecent++;
                remaining.removeFirst();
                continue;
            }
            if (ContainerBlocks.identify(level, next.openPos()) == null) {
                remaining.removeFirst();
                continue;
            }
            if (ContainerBlocks.blockedReason(level, next.found()) != null) {
                markUnreachable(next);
                remaining.removeFirst();
                continue;
            }
            if (!ContainerDiscovery.stillInServerReach(player, next.openPos())) {
                remaining.removeFirst();
                continue;
            }
            // Wait (do not open) while a screen is up, the player sneaks, or the rate limit says no.
            waitingForSneak = player.isSecondaryUseActive();
            if (blockingScreenOpen() || waitingForSneak) {
                return Status.RUNNING;
            }
            long tick = level.getGameTime();
            long now = System.currentTimeMillis();
            if (!rateLimiter.canOpen(tick, now)) {
                return Status.RUNNING;
            }
            remaining.removeFirst();
            rateLimiter.recordOpen(tick, now);
            session = new ContainerSession(next, config.syncTimeoutMs());
            opensStarted = true;
            session.start(minecraft);
            return Status.RUNNING;
        }
        finish(null);
        return Status.DONE;
    }

    private String abortReason(LocalPlayer player) {
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

    private void markUnreachable(ContainerDiscovery.Target target) {
        cache.setState(target.key(), target.found().type(), target.found().positions(), ContainerState.UNREACHABLE);
        unreachable++;
    }

    @Override
    public void cancel(String reason) {
        if (session != null) {
            session.abort(minecraft, reason);
            if (session.endedWithoutMenu()) {
                onSessionEndedWithoutMenu.run();
            }
            session = null;
        }
        remaining.clear();
        finish(reason);
    }

    private void finish(String abortReason) {
        if (finished) {
            return;
        }
        finished = true;
        onFinished.accept(new Summary(refreshed, skippedRecent, ignored, unreachable,
                System.currentTimeMillis() - startedAtMs, abortReason));
    }
}
