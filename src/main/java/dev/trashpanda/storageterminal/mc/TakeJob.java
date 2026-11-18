// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import dev.trashpanda.storageterminal.core.Config;
import dev.trashpanda.storageterminal.core.ContainerState;
import dev.trashpanda.storageterminal.core.ItemKey;
import dev.trashpanda.storageterminal.core.Job;
import dev.trashpanda.storageterminal.core.RateLimiter;
import dev.trashpanda.storageterminal.core.StorageCache;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Takes items of one kind into the player's inventory (SPEC section 9). Visits the containers that
 * hold the item, nearest first, one session each. In each one the slots are re-checked against the
 * fresh contents; if a container has less than the cache said, the rest is taken from the next one.
 * The amount moved is measured from the inventory, never assumed.
 */
final class TakeJob implements ContainerJob {
    /** What happened, for the message to the player. */
    record Result(ItemKey item, String itemName, long wanted, long moved, String problem) {
    }

    private final Minecraft minecraft;
    private final StorageCache cache;
    private final RateLimiter rateLimiter;
    private final Config config;
    private final ItemKey item;
    private final ItemStack reference;
    private final String itemName;
    private final long wanted;
    private final Deque<ContainerDiscovery.Target> stops;
    private final int stopCount;
    private final Consumer<Result> onFinished;
    private final Runnable onSessionEndedWithoutMenu;
    private final JobGuard guard;
    private final int inventoryAtStart;

    private ContainerSession session;
    private TakeOperator operator;
    private boolean inventoryFull;
    private int unreachable;
    private boolean finished;

    TakeJob(Minecraft minecraft, StorageCache cache, RateLimiter rateLimiter, Config config, ItemKey item,
            ItemStack reference, long wanted, List<ContainerDiscovery.Target> nearestFirst,
            Consumer<Result> onFinished, Runnable onSessionEndedWithoutMenu) {
        this.minecraft = minecraft;
        this.cache = cache;
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.item = item;
        this.reference = reference;
        this.itemName = reference.getHoverName().getString();
        this.wanted = wanted;
        this.stops = new ArrayDeque<>(nearestFirst);
        this.stopCount = nearestFirst.size();
        this.onFinished = onFinished;
        this.onSessionEndedWithoutMenu = onSessionEndedWithoutMenu;
        this.guard = new JobGuard(minecraft, config);
        this.inventoryAtStart = TakeOperator.countInInventory(minecraft.player, reference);
    }

    @Override
    public String name() {
        return "take " + item.itemId();
    }

    ItemKey item() {
        return item;
    }

    String itemName() {
        return itemName;
    }

    long wanted() {
        return wanted;
    }

    /** 1-based number of the container being worked on, for "chest 1 of 2". */
    int currentStop() {
        return stopCount - stops.size();
    }

    int stopCount() {
        return stopCount;
    }

    @Override
    public ContainerSession activeSession() {
        return session;
    }

    @Override
    public Status tick() {
        String abort = guard.check();
        if (abort != null) {
            cancel(abort);
            return Status.CANCELLED;
        }
        LocalPlayer player = minecraft.player;
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
                            System.currentTimeMillis(), guard.level().getGameTime());
                    inventoryFull |= operator.inventoryFull();
                    session = null;
                    operator = null;
                }
                case FAILED -> {
                    ContainerDiscovery.Target t = session.target();
                    cache.setState(t.key(), t.found().type(), t.found().positions(), ContainerState.UNREACHABLE);
                    unreachable++;
                    if (session.endedWithoutMenu()) {
                        onSessionEndedWithoutMenu.run();
                    }
                    session = null;
                    operator = null;
                }
                default -> {
                    return Status.RUNNING;
                }
            }
        }
        long remaining = wanted - moved();
        if (remaining <= 0 || inventoryFull) {
            finish(null);
            return Status.DONE;
        }
        while (!stops.isEmpty()) {
            ContainerDiscovery.Target next = stops.peekFirst();
            if (ContainerBlocks.identify(guard.level(), next.openPos()) == null
                    || ContainerBlocks.blockedReason(guard.level(), next.found()) != null
                    || !ContainerDiscovery.stillInServerReach(player, next.openPos())) {
                stops.removeFirst();
                unreachable++;
                continue;
            }
            if (guard.blockingScreenOpen() || player.isSecondaryUseActive()) {
                return Status.RUNNING;
            }
            long tick = guard.level().getGameTime();
            long now = System.currentTimeMillis();
            if (!rateLimiter.canOpen(tick, now)) {
                return Status.RUNNING;
            }
            stops.removeFirst();
            rateLimiter.recordOpen(tick, now);
            operator = new TakeOperator(item, reference, remaining, next.found().slotCount());
            session = new ContainerSession(next, config.syncTimeoutMs(), operator);
            guard.markOpened();
            session.start(minecraft);
            return Status.RUNNING;
        }
        finish(null);
        return Status.DONE;
    }

    /** Measured, not assumed: how much more of the item the inventory holds than at the start. */
    long moved() {
        if (minecraft.player == null) {
            return 0;
        }
        return Math.max(0, TakeOperator.countInInventory(minecraft.player, reference) - inventoryAtStart);
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
        stops.clear();
        finish(reason);
    }

    private void finish(String abortReason) {
        if (finished) {
            return;
        }
        finished = true;
        long moved = moved();
        String problem = abortReason;
        if (problem == null && moved < wanted) {
            if (inventoryFull) {
                problem = "inventory full";
            } else if (unreachable > 0) {
                problem = "a container could not be opened";
            } else {
                problem = "not as many stored as expected";
            }
        }
        onFinished.accept(new Result(item, itemName, wanted, moved, problem));
    }
}
