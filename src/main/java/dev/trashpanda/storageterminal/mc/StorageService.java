// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import dev.trashpanda.storageterminal.StorageTerminal;
import dev.trashpanda.storageterminal.core.Config;
import dev.trashpanda.storageterminal.core.ContainerKey;
import dev.trashpanda.storageterminal.core.ContainerTag;
import dev.trashpanda.storageterminal.core.Job;
import dev.trashpanda.storageterminal.core.JobQueue;
import dev.trashpanda.storageterminal.core.RateLimiter;
import dev.trashpanda.storageterminal.core.SlotStack;
import dev.trashpanda.storageterminal.core.SortOrder;
import dev.trashpanda.storageterminal.core.StorageCache;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Owns the per-world cache and the job queue, and is the single entry point for the
 * mixin hooks. Client thread only.
 */
public final class StorageService {
    private static final StorageService INSTANCE = new StorageService();

    /** How long after the player's own click an opening container is treated as theirs. */
    private static final long MANUAL_OPEN_WINDOW_MS = 2000L;
    /** How long a menu that arrives after one of our timeouts is closed without showing it. */
    private static final long LATE_OPEN_GUARD_MS = 5000L;
    private static final long AUTOSAVE_INTERVAL_MS = 30_000L;

    private final Config config = Config.DEFAULTS;
    private final RateLimiter rateLimiter = new RateLimiter(config.openCooldownTicks(), config.maxOpensPerSecond());
    private final JobQueue jobs = new JobQueue(this::onJobFinished);
    private StorageCache cache = new StorageCache();
    private String worldId;
    private long lastSaveMs;
    private long lateOpenGuardUntilMs;

    // Passive capture of containers the player opens by hand.
    private BlockPos lastManualUsePos;
    private long lastManualUseMs;
    private AbstractContainerMenu passiveMenu;
    private ContainerBlocks.Found passiveTarget;

    // Terminal screen state. The sort order is remembered for the session (config file: M5).
    private SortOrder sortOrder = SortOrder.parse(config.defaultSort());
    private Set<ContainerKey> reachable = Set.of();
    private ScanJob.Summary lastTerminalScan;

    private StorageService() {
    }

    public static StorageService get() {
        return INSTANCE;
    }

    public Config config() {
        return config;
    }

    public StorageCache cache() {
        return cache;
    }

    boolean isBusy() {
        return jobs.isBusy();
    }

    // ---- lifecycle -------------------------------------------------------------------

    void onJoin(Minecraft minecraft) {
        worldId = CacheStore.currentWorldId(minecraft);
        cache = worldId == null ? new StorageCache() : CacheStore.load(worldId);
        lastSaveMs = System.currentTimeMillis();
        StorageTerminal.LOGGER.info("Loaded {} cached containers for {}", cache.size(), worldId);
    }

    void onDisconnect() {
        jobs.cancelAll("disconnected");
        capturePassiveAndForget();
        save();
        cache = new StorageCache();
        worldId = null;
        reachable = Set.of();
        lastManualUsePos = null;
        lateOpenGuardUntilMs = 0;
    }

    void tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        jobs.tick();
        if (passiveMenu != null && minecraft.player.containerMenu != passiveMenu) {
            capturePassiveAndForget();
        }
        if (cache.isDirty() && System.currentTimeMillis() - lastSaveMs > AUTOSAVE_INTERVAL_MS) {
            save();
        }
    }

    void save() {
        lastSaveMs = System.currentTimeMillis();
        if (worldId != null && cache.isDirty()) {
            CacheStore.save(worldId, cache);
        }
    }

    // ---- commands and keys -----------------------------------------------------------

    /** Starts a scan of everything in reach. Returns a message if it could not start. */
    String startScan(Minecraft minecraft, boolean force, Consumer<ScanJob.Summary> onFinished) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return "Not in a world.";
        }
        if (jobs.isBusy()) {
            return "Already busy; wait for the current job to finish.";
        }
        if (player.isSecondaryUseActive()) {
            return "Stop sneaking to scan containers.";
        }
        List<ContainerDiscovery.Target> targets = ContainerDiscovery.findInReach(minecraft.level, player, config);
        jobs.enqueue(new ScanJob(minecraft, cache, rateLimiter, config, force, targets, summary -> {
            save();
            onFinished.accept(summary);
        }, this::armLateOpenGuard));
        return null;
    }

    // ---- terminal ----------------------------------------------------------------------

    /** Opens the terminal from the cache at once, then refreshes containers in reach in the background. */
    void openTerminal(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        refreshReachable(minecraft);
        lastTerminalScan = null;
        minecraft.gui.setScreen(new TerminalScreen());
        startTerminalScan(minecraft, false);
    }

    /** Background refresh for the terminal. {@code force} rescans containers refreshed moments ago too. */
    void startTerminalScan(Minecraft minecraft, boolean force) {
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (jobs.isBusy()) {
            if (!force) {
                return;
            }
            jobs.cancelAll("rescan");
        }
        List<ContainerDiscovery.Target> targets = refreshReachable(minecraft);
        jobs.enqueue(new ScanJob(minecraft, cache, rateLimiter, config, force, targets, summary -> {
            lastTerminalScan = summary;
            save();
            if (minecraft.player != null && minecraft.level != null) {
                refreshReachable(minecraft);
            }
        }, this::armLateOpenGuard));
    }

    /** The terminal closed: stop all work cleanly and save. */
    void onTerminalClosed() {
        jobs.cancelAll("terminal closed");
        save();
    }

    Set<ContainerKey> reachable() {
        return reachable;
    }

    SortOrder sortOrder() {
        return sortOrder;
    }

    void setSortOrder(SortOrder order) {
        sortOrder = order;
    }

    ScanJob.Summary lastTerminalScan() {
        return lastTerminalScan;
    }

    /** The running scan, if any; the terminal shows its progress. */
    ScanJob runningScan() {
        return currentScan();
    }

    private List<ContainerDiscovery.Target> refreshReachable(Minecraft minecraft) {
        List<ContainerDiscovery.Target> targets = ContainerDiscovery.findInReach(minecraft.level, minecraft.player, config);
        Set<ContainerKey> keys = new HashSet<>();
        for (ContainerDiscovery.Target target : targets) {
            keys.add(target.key());
        }
        reachable = Set.copyOf(keys);
        return targets;
    }

    void cancelJobs(String reason) {
        jobs.cancelAll(reason);
    }

    /** Toggles IGNORE on the container under the crosshair. Returns the message to show. */
    String toggleIgnoreAtCrosshair(Minecraft minecraft) {
        HitResult hit = minecraft.hitResult;
        if (minecraft.level == null || !(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            return "Look at a chest, barrel or shulker box.";
        }
        ContainerBlocks.Found found = ContainerBlocks.identify(minecraft.level, blockHit.getBlockPos());
        if (found == null) {
            return "Look at a chest, barrel or shulker box.";
        }
        boolean nowIgnored = cache.toggleTag(found.key(), found.type(), found.positions(), ContainerTag.IGNORE);
        save();
        String what = describe(found);
        return nowIgnored ? "Ignoring " + what + ": it will never be opened or used."
                : "No longer ignoring " + what + ".";
    }

    void clearCache() {
        jobs.cancelAll("cache cleared");
        cache.clear();
        if (worldId != null) {
            CacheStore.delete(worldId);
        }
        cache.markClean();
    }

    static String describe(ContainerBlocks.Found found) {
        String kind = switch (found.type()) {
            case CHEST -> found.isDouble() ? "double chest" : "chest";
            case TRAPPED_CHEST -> found.isDouble() ? "double trapped chest" : "trapped chest";
            case BARREL -> "barrel";
            case SHULKER_BOX -> "shulker box";
        };
        return kind + " at " + found.key().pos();
    }

    // ---- hooks called from mixins ----------------------------------------------------

    /** The player right-clicked a block themselves (not one of our sessions). */
    public void onPlayerUseItemOn(BlockHitResult hit) {
        if (activeSessionWaiting() != null) {
            return;
        }
        lastManualUsePos = hit.getBlockPos();
        lastManualUseMs = System.currentTimeMillis();
    }

    /**
     * A container menu is about to be opened by the server. Returns true if we took it
     * (our session's menu, or a late one we close at once), so no screen is shown.
     */
    public boolean onOpenMenu(Minecraft minecraft, MenuType<?> type, int containerId) {
        ContainerSession waiting = activeSessionWaiting();
        if (waiting != null) {
            waiting.claimMenu(minecraft, type, containerId);
            return true;
        }
        long now = System.currentTimeMillis();
        boolean playerJustClicked = lastManualUsePos != null && now - lastManualUseMs < MANUAL_OPEN_WINDOW_MS;
        if (now < lateOpenGuardUntilMs && !playerJustClicked && isContainerMenu(type)) {
            // A container we gave up on opened after all: close it without ever showing it.
            ClientPacketListener connection = minecraft.getConnection();
            if (connection != null) {
                connection.send(new ServerboundContainerClosePacket(containerId));
            }
            lateOpenGuardUntilMs = 0;
            StorageTerminal.LOGGER.info("Closed a container that opened after its timeout");
            return true;
        }
        return false;
    }

    /** Vanilla just opened a container screen (not ours). Track it if the player opened a known container. */
    public void afterVanillaOpenMenu(Minecraft minecraft) {
        capturePassiveAndForget();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || lastManualUsePos == null
                || System.currentTimeMillis() - lastManualUseMs > MANUAL_OPEN_WINDOW_MS) {
            return;
        }
        ContainerBlocks.Found found = ContainerBlocks.identify(minecraft.level, lastManualUsePos);
        AbstractContainerMenu menu = player.containerMenu;
        if (found != null && menu != player.inventoryMenu && menuType(menu) == found.expectedMenu()) {
            passiveMenu = menu;
            passiveTarget = found;
        }
        lastManualUsePos = null;
    }

    public void onContentSynced(int containerId) {
        ScanJob scan = currentScan();
        if (scan != null && scan.activeSession() != null) {
            scan.activeSession().onContentSynced(containerId);
        }
        if (passiveMenu != null && passiveMenu.containerId == containerId) {
            capturePassive();
        }
    }

    /** Returns true if the closed menu was ours, so vanilla must not close the current screen. */
    public boolean onServerClosedMenu(Minecraft minecraft, int containerId) {
        ScanJob scan = currentScan();
        return scan != null && scan.activeSession() != null
                && scan.activeSession().onServerClosed(minecraft, containerId);
    }

    // ---- internals -------------------------------------------------------------------

    private ContainerSession activeSessionWaiting() {
        ScanJob scan = currentScan();
        if (scan == null || scan.activeSession() == null) {
            return null;
        }
        ContainerSession session = scan.activeSession();
        return session.isWaitingForMenu() ? session : null;
    }

    private ScanJob currentScan() {
        return jobs.current() instanceof ScanJob scan ? scan : null;
    }

    private void armLateOpenGuard() {
        lateOpenGuardUntilMs = System.currentTimeMillis() + LATE_OPEN_GUARD_MS;
    }

    private void onJobFinished(Job job, Job.Status status) {
        StorageTerminal.LOGGER.debug("Job {} finished: {}", job.name(), status);
    }

    private void capturePassive() {
        Minecraft minecraft = Minecraft.getInstance();
        if (passiveMenu == null || passiveTarget == null || minecraft.level == null) {
            return;
        }
        int count = passiveTarget.slotCount();
        List<SlotStack> slots = new ArrayList<>(count);
        for (int i = 0; i < count && i < passiveMenu.slots.size(); i++) {
            slots.add(ItemKeys.toSlot(passiveMenu.slots.get(i).getItem(), minecraft.level.registryAccess()));
        }
        cache.putSnapshot(passiveTarget.key(), passiveTarget.type(), passiveTarget.positions(), slots,
                System.currentTimeMillis(), minecraft.level.getGameTime());
    }

    private void capturePassiveAndForget() {
        if (passiveMenu != null) {
            capturePassive();
        }
        passiveMenu = null;
        passiveTarget = null;
    }

    private static boolean isContainerMenu(MenuType<?> type) {
        return type == MenuType.GENERIC_9x3 || type == MenuType.GENERIC_9x6 || type == MenuType.SHULKER_BOX;
    }

    private static MenuType<?> menuType(AbstractContainerMenu menu) {
        try {
            return menu.getType();
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }
}
