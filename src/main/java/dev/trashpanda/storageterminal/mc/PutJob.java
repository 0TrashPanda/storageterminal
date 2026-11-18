// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import dev.trashpanda.storageterminal.core.Config;
import dev.trashpanda.storageterminal.core.ContainerKey;
import dev.trashpanda.storageterminal.core.ContainerState;
import dev.trashpanda.storageterminal.core.ItemKey;
import dev.trashpanda.storageterminal.core.RateLimiter;
import dev.trashpanda.storageterminal.core.StorageCache;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Deposits inventory stacks into containers in reach (SPEC section 10). Containers are visited in
 * the order the planner gave (per item: partial stacks, then same item, then free space, nearest
 * first); at each one, every pending stack planned for it is shift-clicked in. What does not fit
 * goes on to the next container. The amount stored is measured from the inventory.
 */
final class PutJob implements ContainerJob {
    /** One inventory stack to deposit. */
    record Request(int inventoryIndex, ItemKey item, ItemStack reference) {
    }

    record Result(String what, long wanted, long moved, String problem) {
    }

    private final Minecraft minecraft;
    private final StorageCache cache;
    private final RateLimiter rateLimiter;
    private final Config config;
    private final List<Request> requests;
    private final Map<ItemKey, Set<ContainerKey>> plannedStops;
    private final Deque<ContainerDiscovery.Target> visits;
    private final int visitCount;
    private final String what;
    private final Consumer<Result> onFinished;
    private final Runnable onSessionEndedWithoutMenu;
    private final JobGuard guard;
    private final Map<ItemKey, Integer> countAtStart = new LinkedHashMap<>();
    private final Map<ItemKey, ItemStack> references = new LinkedHashMap<>();
    private final long wanted;

    private ContainerSession session;
    private int unreachable;
    private boolean finished;

    PutJob(Minecraft minecraft, StorageCache cache, RateLimiter rateLimiter, Config config, List<Request> requests,
            Map<ItemKey, Set<ContainerKey>> plannedStops, List<ContainerDiscovery.Target> visits, String what,
            Consumer<Result> onFinished, Runnable onSessionEndedWithoutMenu) {
        this.minecraft = minecraft;
        this.cache = cache;
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.requests = List.copyOf(requests);
        this.plannedStops = plannedStops;
        this.visits = new ArrayDeque<>(visits);
        this.visitCount = visits.size();
        this.what = what;
        this.onFinished = onFinished;
        this.onSessionEndedWithoutMenu = onSessionEndedWithoutMenu;
        this.guard = new JobGuard(minecraft, config);
        long total = 0;
        for (Request request : requests) {
            references.putIfAbsent(request.item(), request.reference());
            total += minecraft.player.getInventory().getItem(request.inventoryIndex()).getCount();
        }
        for (Map.Entry<ItemKey, ItemStack> e : references.entrySet()) {
            countAtStart.put(e.getKey(), TakeOperator.countInInventory(minecraft.player, e.getValue()));
        }
        this.wanted = total;
    }

    @Override
    public String name() {
        return "put " + what;
    }

    @Override
    public ContainerSession activeSession() {
        return session;
    }

    String what() {
        return what;
    }

    int currentVisit() {
        return visitCount - visits.size();
    }

    int visitCount() {
        return visitCount;
    }

    /** Inventory indexes still waiting to be stored (dimmed in the terminal). */
    Set<Integer> pendingInventorySlots() {
        Set<Integer> slots = new HashSet<>();
        if (minecraft.player == null) {
            return slots;
        }
        for (Request request : requests) {
            if (stillHolds(minecraft.player, request)) {
                slots.add(request.inventoryIndex());
            }
        }
        return slots;
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
                    session = null;
                }
                case FAILED -> {
                    ContainerDiscovery.Target t = session.target();
                    cache.setState(t.key(), t.found().type(), t.found().positions(), ContainerState.UNREACHABLE);
                    unreachable++;
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
        while (!visits.isEmpty()) {
            ContainerDiscovery.Target next = visits.peekFirst();
            Map<Integer, ItemStack> here = stacksFor(player, next.key());
            if (here.isEmpty()) {
                visits.removeFirst();
                continue;
            }
            if (ContainerBlocks.identify(guard.level(), next.openPos()) == null
                    || ContainerBlocks.blockedReason(guard.level(), next.found()) != null
                    || !ContainerDiscovery.stillInServerReach(player, next.openPos())) {
                visits.removeFirst();
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
            visits.removeFirst();
            rateLimiter.recordOpen(tick, now);
            session = new ContainerSession(next, config.syncTimeoutMs(), new PutOperator(here));
            guard.markOpened();
            session.start(minecraft);
            return Status.RUNNING;
        }
        finish(null);
        return Status.DONE;
    }

    /** Pending stacks whose item was planned for this container. */
    private Map<Integer, ItemStack> stacksFor(LocalPlayer player, ContainerKey container) {
        Map<Integer, ItemStack> out = new LinkedHashMap<>();
        for (Request request : requests) {
            Set<ContainerKey> stops = plannedStops.get(request.item());
            if (stops != null && stops.contains(container) && stillHolds(player, request)) {
                out.put(request.inventoryIndex(), request.reference());
            }
        }
        return out;
    }

    private static boolean stillHolds(LocalPlayer player, Request request) {
        ItemStack now = player.getInventory().getItem(request.inventoryIndex());
        return !now.isEmpty() && ItemStack.isSameItemSameComponents(now, request.reference());
    }

    /** Measured: how much less of the requested items the inventory holds than at the start. */
    long moved() {
        if (minecraft.player == null) {
            return 0;
        }
        long moved = 0;
        for (Map.Entry<ItemKey, ItemStack> e : references.entrySet()) {
            int now = TakeOperator.countInInventory(minecraft.player, e.getValue());
            moved += Math.max(0, countAtStart.get(e.getKey()) - now);
        }
        return moved;
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
        visits.clear();
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
            problem = unreachable > 0 ? "a container could not be opened" : "no free space";
        }
        onFinished.accept(new Result(what, wanted, moved, problem));
    }
}
