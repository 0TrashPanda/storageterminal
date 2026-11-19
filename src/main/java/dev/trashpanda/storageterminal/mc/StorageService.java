// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import dev.trashpanda.storageterminal.StorageTerminal;
import dev.trashpanda.storageterminal.core.Config;
import dev.trashpanda.storageterminal.core.ContainerKey;
import dev.trashpanda.storageterminal.core.ContainerRecord;
import dev.trashpanda.storageterminal.core.CountFormat;
import dev.trashpanda.storageterminal.core.ItemKey;
import dev.trashpanda.storageterminal.core.PutPlanner;
import dev.trashpanda.storageterminal.core.ContainerTag;
import dev.trashpanda.storageterminal.core.Job;
import dev.trashpanda.storageterminal.core.JobQueue;
import dev.trashpanda.storageterminal.core.RateLimiter;
import dev.trashpanda.storageterminal.core.SlotStack;
import dev.trashpanda.storageterminal.core.SortOrder;
import dev.trashpanda.storageterminal.core.StorageCache;
import dev.trashpanda.storageterminal.core.TakePlanner;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;
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

    private Config config = Config.DEFAULTS;
    private RateLimiter rateLimiter = new RateLimiter(config.openCooldownTicks(), config.maxOpensPerSecond());
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

    // Terminal screen state. The sort order is saved in the config file.
    private SortOrder sortOrder = SortOrder.parse(config.defaultSort());
    private Set<ContainerKey> reachable = Set.of();
    private ScanJob.Summary lastTerminalScan;

    /** Result of the last take/put, shown in the terminal's status line for a few seconds. */
    enum FeedbackKind {
        SUCCESS,
        PARTIAL,
        FAILED
    }

    record Feedback(FeedbackKind kind, String text, long atMillis) {
    }

    private static final long FEEDBACK_MS = 5000L;
    private Feedback feedback;

    // Shift + wheel: one item per notch. Notches that arrive while a wheel job runs are added up
    // (+ take, - store) and done as one job afterwards.
    private boolean wheelJobRunning;
    private ItemKey wheelItem;
    private int wheelInventoryIndex = -1;
    private int wheelQueued;
    private boolean wheelFailed;

    // Shift-click and Shift-drag: stacks and entries the mouse passed over while a job was running
    // wait here and run one after another. Consecutive stores are merged into one job.
    private sealed interface Queued permits QueuedStore, QueuedTake {
    }

    /** Inventory slots to store, with what each held when it was dragged over. */
    private record QueuedStore(Map<Integer, ItemStack> slots) implements Queued {
    }

    private record QueuedTake(ItemKey item) implements Queued {
    }

    private final Deque<Queued> queued = new ArrayDeque<>();

    // The cursor in the terminal (SPEC 12b). A held stack never sits on the real cursor between
    // clicks: it stays in an inventory slot (heldSlot) and is drawn at the mouse. Each click on it
    // becomes real clicks in the player's own inventory menu, all sent at once: pick the stack up,
    // do the player's click, put what is left back. Vanilla then handles placing, swapping,
    // merging and bundles, and nothing can be dropped by a container closing.
    private int heldSlot = -1;
    /** Slot a take-to-cursor fills; it becomes the held slot when the take ends. */
    private int cursorTakeSlot = -1;

    /** A click on the held stack, waiting until no container of ours is open. */
    private sealed interface CursorOp permits ClickOp, SplitOp, DropOp {
    }

    /** Held stack clicked onto inventory slot {@code target}. */
    private record ClickOp(int target, boolean right) implements CursorOp {
    }

    /** Right-click on a stack with nothing held: pick up half. */
    private record SplitOp(int target) implements CursorOp {
    }

    /** Click outside the panel: drop the held stack (right: one item). */
    private record DropOp(boolean right) implements CursorOp {
    }

    private final Deque<CursorOp> cursorOps = new ArrayDeque<>();
    /** What a take-to-cursor will put on the cursor, shown there before the take is done. */
    private ItemStack cursorTakePreview = ItemStack.EMPTY;
    /** A take into the inventory: the slot it fills and what that slot will hold, shown before it is done. */
    private int incomingSlot = -1;
    private ItemStack incomingPreview = ItemStack.EMPTY;
    /** The held stack is being stored: 0 none, -1 all of it, else this many items. */
    private long storingHeld;

    // Shown at once: how each item's stored total changes when the running move is done.
    private final Map<ItemKey, Long> predicted = new LinkedHashMap<>();
    private int predictionId;

    private StorageService() {
    }

    public static StorageService get() {
        return INSTANCE;
    }

    public Config config() {
        return config;
    }

    /** Loads {@code config/storageterminal/config.json} (created with defaults on first start). */
    void loadConfig() {
        config = ConfigStore.load();
        rateLimiter = new RateLimiter(config.openCooldownTicks(), config.maxOpensPerSecond());
        sortOrder = SortOrder.parse(config.defaultSort());
    }

    /** Logs at INFO when debugLogging is on, else at DEBUG (hidden by default). */
    void debug(String message, Object... args) {
        if (config.debugLogging()) {
            StorageTerminal.LOGGER.info(message, args);
        } else {
            StorageTerminal.LOGGER.debug(message, args);
        }
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
        debug("Loaded {} cached containers for {}", cache.size(), worldId);
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
        runCursorOps(minecraft);
        jobs.tick();
        continueWheel(minecraft);
        continueQueued(minecraft);
        kickJobs();
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

    /** Starts taking {@code item} for a grid click. Problems are reported through {@link #feedback()}. */
    void startTake(Minecraft minecraft, ItemKey item, TakePlanner.Mode mode) {
        startTake(minecraft, item, mode, 1, -1);
    }

    /**
     * {@code count} is used by {@link TakePlanner.Mode#ONE} only (wheel notches added up);
     * {@code preferredInventoryIndex} is the stack to put the items on, or -1. False if nothing started.
     */
    private boolean startTake(Minecraft minecraft, ItemKey item, TakePlanner.Mode mode, long count,
            int preferredInventoryIndex) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return false;
        }
        if (runningTake() != null || runningPut() != null) {
            showFeedback(minecraft, FeedbackKind.FAILED, "Busy: wait for the current job to finish");
            return false;
        }
        if (player.isSecondaryUseActive()) {
            showFeedback(minecraft, FeedbackKind.FAILED, "Stop sneaking to take items");
            return false;
        }
        ItemStack reference = ItemKeys.toStack(item, 1, minecraft.level.registryAccess());
        if (reference.isEmpty()) {
            showFeedback(minecraft, FeedbackKind.FAILED, "Unknown item " + item.itemId());
            return false;
        }
        int maxStack = reference.getMaxStackSize();
        List<SlotStack> inventory = new ArrayList<>(36);
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            inventory.add(stack.isEmpty() ? SlotStack.EMPTY
                    : ItemStack.isSameItemSameComponents(stack, reference) ? new SlotStack(item, stack.getCount())
                    : new SlotStack(ItemKey.of("storageterminal:other"), stack.getCount()));
        }
        int room = TakePlanner.inventoryRoom(item, maxStack, inventory);

        // Containers in reach that (per the cache) hold the item, nearest first.
        jobs.cancelAll("take");
        List<ContainerDiscovery.Target> stops = new ArrayList<>();
        long stored = 0;
        for (ContainerDiscovery.Target target : refreshReachable(minecraft)) {
            ContainerRecord record = cache.get(target.key());
            if (record == null || !record.isUsable()) {
                continue;
            }
            long here = record.slots().stream().filter(s -> !s.isEmpty() && s.item().equals(item))
                    .mapToLong(SlotStack::count).sum();
            if (here > 0) {
                stops.add(target);
                stored += here;
            }
        }
        String name = reference.getHoverName().getString();
        if (stored == 0) {
            showFeedback(minecraft, FeedbackKind.FAILED, "No " + name + " in reach");
            return false;
        }
        long amount = mode == TakePlanner.Mode.ONE ? Math.min(Math.min(count, stored), room)
                : TakePlanner.amountToTake(mode, stored, maxStack, room);
        if (amount <= 0) {
            showFeedback(minecraft, FeedbackKind.FAILED, "Inventory full");
            return false;
        }
        if (preferredInventoryIndex < 0 && mode != TakePlanner.Mode.FILL) {
            preferredInventoryIndex = targetSlot(player, reference, (int) amount);
        }
        jobs.enqueue(new TakeJob(minecraft, cache, rateLimiter, config, item, reference, amount,
                preferredInventoryIndex, stops, result -> onTakeFinished(minecraft, result), this::armLateOpenGuard));
        setPrediction(Map.of(item, -amount));
        if (preferredInventoryIndex >= 0) {
            ItemStack there = player.getInventory().getItem(preferredInventoryIndex);
            int after = (int) Math.min(maxStack, amount + (ItemStack.isSameItemSameComponents(there, reference) ? there.getCount() : 0));
            incomingSlot = preferredInventoryIndex;
            incomingPreview = reference.copyWithCount(after);
        }
        kickJobs();
        return true;
    }

    private void onTakeFinished(Minecraft minecraft, TakeJob.Result result) {
        save();
        clearPrediction();
        if (cursorTakeSlot >= 0 && minecraft.player != null) {
            if (!minecraft.player.getInventory().getItem(cursorTakeSlot).isEmpty()) {
                heldSlot = cursorTakeSlot;
            }
            cursorTakeSlot = -1;
        }
        cursorTakePreview = ItemStack.EMPTY;
        incomingSlot = -1;
        incomingPreview = ItemStack.EMPTY;
        wheelFailed |= result.problem() != null;
        String took = "Took " + CountFormat.exact(result.moved());
        if (result.problem() == null) {
            showFeedback(minecraft, FeedbackKind.SUCCESS, took + " " + result.itemName());
        } else if (result.moved() > 0) {
            showFeedback(minecraft, FeedbackKind.PARTIAL, took + " of " + CountFormat.exact(result.wanted()) + " "
                    + result.itemName() + ": " + result.problem());
        } else {
            showFeedback(minecraft, FeedbackKind.FAILED, "Took no " + result.itemName() + ": " + result.problem());
        }
    }

    void showFeedback(Minecraft minecraft, FeedbackKind kind, String text) {
        feedback = new Feedback(kind, text, System.currentTimeMillis());
        debug("[{}] {}", kind, text);
        if (minecraft.player != null && !(minecraft.gui.screen() instanceof TerminalScreen)) {
            minecraft.player.sendOverlayMessage(Component.literal(text));
        }
    }

    /** The latest take/put result while it is recent, else null. */
    Feedback feedback() {
        return feedback != null && System.currentTimeMillis() - feedback.atMillis() < FEEDBACK_MS ? feedback : null;
    }

    // ---- cursor ------------------------------------------------------------------------

    /** Inventory slot (0-35) of the held stack, or -1. Forgets it once the slot is empty. */
    int heldSlot() {
        Minecraft minecraft = Minecraft.getInstance();
        if (heldSlot >= 0 && (minecraft.player == null || minecraft.player.getInventory().getItem(heldSlot).isEmpty())) {
            heldSlot = -1;
        }
        return heldSlot;
    }

    /**
     * What the cursor shows: the held stack, or what a running take will put there, minus what a
     * running store takes away. Shown before the move is done, so clicks feel instant.
     */
    ItemStack heldStack(Minecraft minecraft) {
        if (cursorTakeSlot >= 0) {
            return cursorTakePreview;
        }
        int slot = heldSlot();
        if (slot < 0 || minecraft.player == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = minecraft.player.getInventory().getItem(slot);
        if (storingHeld < 0) {
            return ItemStack.EMPTY;
        }
        if (storingHeld > 0) {
            return stack.getCount() > storingHeld ? stack.copyWithCount((int) (stack.getCount() - storingHeld)) : ItemStack.EMPTY;
        }
        return stack;
    }

    /** The inventory slot drawn empty because its stack is on the cursor (held or being taken there), or -1. */
    int cursorSlot() {
        return cursorTakeSlot >= 0 ? cursorTakeSlot : heldSlot();
    }

    /** True while a take to the cursor, a store from it or a click on it is still on its way. */
    boolean cursorBusy() {
        return cursorTakeSlot >= 0 || storingHeld != 0 || !cursorOps.isEmpty();
    }

    /** How stored totals change once the running move is done (see {@link #predictionId()}). */
    Map<ItemKey, Long> predicted() {
        return predicted;
    }

    /** Changes whenever {@link #predicted()} does. */
    int predictionId() {
        return predictionId;
    }

    private void setPrediction(Map<ItemKey, Long> delta) {
        predicted.clear();
        predicted.putAll(delta);
        predictionId++;
    }

    private void clearPrediction() {
        if (!predicted.isEmpty()) {
            predicted.clear();
            predictionId++;
        }
    }

    /** A job made outside the tick starts right away instead of on the next tick. */
    private void kickJobs() {
        if (jobs.current() == null && jobs.isBusy()) {
            jobs.tick();
        }
    }

    /** Plain click on a grid entry with nothing held: a stack (or half) onto the cursor. */
    void takeToCursor(Minecraft minecraft, ItemKey item, TakePlanner.Mode mode) {
        if (cursorBusy()) {
            return;
        }
        int park = emptySlot(minecraft, -1);
        if (park < 0) {
            showFeedback(minecraft, FeedbackKind.FAILED, "Inventory full: no free slot to hold items");
            return;
        }
        if (startTake(minecraft, item, mode, 1, park)) {
            cursorTakeSlot = park;
            TakeJob take = runningTake();
            cursorTakePreview = take == null ? ItemStack.EMPTY
                    : ItemKeys.toStack(item, (int) take.wanted(), minecraft.level.registryAccess());
        }
    }

    /** Plain click on the grid while holding: store the held stack (right: one item). */
    void storeHeld(Minecraft minecraft, boolean one) {
        int slot = heldSlot();
        if (slot < 0 || cursorBusy()) {
            return;
        }
        if (startPut(minecraft, List.of(slot), one ? 1 : PutOperator.WHOLE_STACKS, true)) {
            storingHeld = one ? 1 : -1;
        }
    }

    /** Plain click on inventory slot {@code index}: pick up, place, swap or merge, like vanilla. */
    void cursorClickInventory(Minecraft minecraft, int index, boolean right) {
        if (minecraft.player == null || cursorTakeSlot >= 0 || storingHeld != 0) {
            return; // The held stack is still on its way into or out of storage.
        }
        if (heldSlot() < 0 && cursorOps.isEmpty()) {
            ItemStack stack = minecraft.player.getInventory().getItem(index);
            if (stack.isEmpty()) {
                return;
            }
            if (!right || stack.getCount() == 1) {
                heldSlot = index; // Picking a whole stack up needs no click: it stays where it is.
                return;
            }
            cursorOps.addLast(new SplitOp(index));
        } else {
            cursorOps.addLast(new ClickOp(index, right));
        }
        runCursorOps(minecraft);
    }

    /** Click outside the panel while holding: drop it, like vanilla (right: one item). */
    void dropHeld(Minecraft minecraft, boolean right) {
        if (storingHeld != 0 || cursorTakeSlot >= 0 || (heldSlot() < 0 && cursorOps.isEmpty())) {
            return;
        }
        cursorOps.addLast(new DropOp(right));
        runCursorOps(minecraft);
    }

    /**
     * Sends waiting cursor clicks, but only while none of our containers is open or opening:
     * the clicks go to the player's own inventory menu, which the server only accepts then.
     */
    private void runCursorOps(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (cursorOps.isEmpty() || player == null || minecraft.gameMode == null) {
            return;
        }
        if (activeSession() != null || player.containerMenu != player.inventoryMenu) {
            return;
        }
        while (!cursorOps.isEmpty()) {
            CursorOp op = cursorOps.pollFirst();
            if (!player.inventoryMenu.getCarried().isEmpty()) {
                // Never expected: something is on the real cursor. Leave it to vanilla, do nothing.
                StorageTerminal.LOGGER.warn("Cursor click skipped: {} is on the real cursor", player.inventoryMenu.getCarried());
                cursorOps.clear();
                return;
            }
            switch (op) {
                case ClickOp click -> doClick(minecraft, click.target(), click.right());
                case SplitOp split -> doSplit(minecraft, split.target());
                case DropOp drop -> doDrop(minecraft, drop.right());
            }
        }
    }

    private void doClick(Minecraft minecraft, int target, boolean right) {
        int held = heldSlot();
        if (held < 0) {
            return;
        }
        if (target == held) {
            if (!right) {
                heldSlot = -1; // Put down where it already is.
                return;
            }
            // One item into its own slot; the rest needs another slot to stay held.
            int park = emptySlot(minecraft, held);
            if (park < 0) {
                return;
            }
            click(minecraft, held, 0);
            click(minecraft, held, 1);
            click(minecraft, park, 0);
            heldSlot = park;
            return;
        }
        click(minecraft, held, 0);
        click(minecraft, target, right ? 1 : 0);
        putBack(minecraft, held);
    }

    private void doSplit(Minecraft minecraft, int target) {
        int park = emptySlot(minecraft, target);
        if (park < 0) {
            showFeedback(minecraft, FeedbackKind.FAILED, "Inventory full: no free slot to hold items");
            return;
        }
        click(minecraft, target, 1);
        click(minecraft, park, 0);
        heldSlot = park;
    }

    private void doDrop(Minecraft minecraft, boolean right) {
        int held = heldSlot();
        if (held < 0) {
            return;
        }
        click(minecraft, held, 0);
        clickMenuSlot(minecraft, AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, right ? 1 : 0);
        putBack(minecraft, held);
    }

    /** Whatever is still on the real cursor goes back to {@code slot} (empty: it was just picked up) and stays held. */
    private void putBack(Minecraft minecraft, int slot) {
        if (!minecraft.player.inventoryMenu.getCarried().isEmpty()) {
            click(minecraft, slot, 0);
        }
        heldSlot = minecraft.player.getInventory().getItem(slot).isEmpty() ? -1 : slot;
    }

    /** A click on inventory slot {@code index} (0-35) in the player's own menu. */
    private void click(Minecraft minecraft, int index, int button) {
        clickMenuSlot(minecraft, PutOperator.menuSlotFor(minecraft, minecraft.player.inventoryMenu, index), button);
    }

    private void clickMenuSlot(Minecraft minecraft, int menuSlot, int button) {
        minecraft.gameMode.handleContainerInput(minecraft.player.inventoryMenu.containerId, menuSlot, button,
                ContainerInput.PICKUP, minecraft.player);
    }

    /**
     * A slot that takes {@code amount} of the item in one go: a stack of it with room (main
     * inventory first), else an empty main slot, else an empty hotbar slot; -1 if none.
     */
    private static int targetSlot(LocalPlayer player, ItemStack reference, int amount) {
        int empty = -1;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = pass == 0 ? 9 : 0; i < (pass == 0 ? 36 : 9); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.isEmpty()) {
                    if (empty < 0) {
                        empty = i;
                    }
                } else if (ItemStack.isSameItemSameComponents(stack, reference)
                        && stack.getCount() + amount <= stack.getMaxStackSize()) {
                    return i;
                }
            }
        }
        return empty;
    }

    /** Inventory slot a running take fills, or -1 (see {@link #incomingStack()}). */
    int incomingSlot() {
        return incomingSlot;
    }

    /** What {@link #incomingSlot()} will hold when the take is done. */
    ItemStack incomingStack() {
        return incomingPreview;
    }

    /** Container open/close sounds are muted for us while the terminal is open or a job runs. */
    public boolean shouldMute(net.minecraft.resources.Identifier sound) {
        if (!MUTED_SOUNDS.contains(sound.toString())) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.gui.screen() instanceof TerminalScreen || jobs.isBusy();
    }

    private static final Set<String> MUTED_SOUNDS = Set.of(
            "minecraft:block.chest.open", "minecraft:block.chest.close",
            "minecraft:block.barrel.open", "minecraft:block.barrel.close",
            "minecraft:block.shulker_box.open", "minecraft:block.shulker_box.close",
            "minecraft:block.copper_chest.open", "minecraft:block.copper_chest.close");

    /** An empty slot to hold items in: main inventory first, then the hotbar; never {@code except}. */
    private int emptySlot(Minecraft minecraft, int except) {
        if (minecraft.player == null) {
            return -1;
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = pass == 0 ? 9 : 0; i < (pass == 0 ? 36 : 9); i++) {
                if (i != except && minecraft.player.getInventory().getItem(i).isEmpty()) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** The take that runs, or is queued to run next (a job only starts on the next tick). */
    TakeJob runningTake() {
        return jobs.currentOrPending(TakeJob.class);
    }

    /** The put that runs, or is queued to run next. */
    PutJob runningPut() {
        return jobs.currentOrPending(PutJob.class);
    }

    /**
     * Deposits the given inventory slots (0-35). {@code what} names them in messages: an item
     * name for one item type, else "items".
     */
    void startPut(Minecraft minecraft, List<Integer> inventorySlots) {
        startPut(minecraft, inventorySlots, PutOperator.WHOLE_STACKS, false);
    }

    private boolean startPut(Minecraft minecraft, List<Integer> inventorySlots, long limit) {
        return startPut(minecraft, inventorySlots, limit, false);
    }

    /**
     * Stores at most {@code limit} items. The held stack is left alone unless {@code includeHeld}
     * (Deposit all, Alt-click and the rest must not store what the player holds). False if nothing started.
     */
    private boolean startPut(Minecraft minecraft, List<Integer> inventorySlots, long limit, boolean includeHeld) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return false;
        }
        if (runningTake() != null || runningPut() != null) {
            showFeedback(minecraft, FeedbackKind.FAILED, "Busy: wait for the current job to finish");
            return false;
        }
        if (player.isSecondaryUseActive()) {
            showFeedback(minecraft, FeedbackKind.FAILED, "Stop sneaking to store items");
            return false;
        }
        List<PutJob.Request> requests = new ArrayList<>();
        Map<ItemKey, ItemStack> types = new LinkedHashMap<>();
        for (int index : inventorySlots) {
            ItemStack stack = player.getInventory().getItem(index);
            if (stack.isEmpty() || (index == heldSlot() && !includeHeld)) {
                continue;
            }
            ItemKey key = ItemKeys.toKey(stack, minecraft.level.registryAccess());
            ItemStack reference = stack.copyWithCount(1);
            requests.add(new PutJob.Request(index, key, reference));
            types.putIfAbsent(key, reference);
        }
        if (requests.isEmpty()) {
            return false;
        }
        String what = types.size() == 1 ? types.values().iterator().next().getHoverName().getString() : "items";

        jobs.cancelAll("take");
        List<ContainerDiscovery.Target> targets = refreshReachable(minecraft);
        Map<ContainerKey, ContainerDiscovery.Target> byKey = new LinkedHashMap<>();
        List<ContainerRecord> nearestFirst = new ArrayList<>();
        for (ContainerDiscovery.Target target : targets) {
            ContainerRecord record = cache.get(target.key());
            if (record != null && record.hasSnapshot()) {
                byKey.put(target.key(), target);
                nearestFirst.add(record);
            }
        }
        Map<ItemKey, Set<ContainerKey>> planned = new LinkedHashMap<>();
        Set<ContainerKey> visitOrder = new LinkedHashSet<>();
        for (Map.Entry<ItemKey, ItemStack> type : types.entrySet()) {
            boolean shulkerItem = type.getValue().getItem() instanceof BlockItem block
                    && block.getBlock() instanceof ShulkerBoxBlock;
            List<PutPlanner.PutStop> stops = PutPlanner.plan(type.getKey(), type.getValue().getMaxStackSize(),
                    nearestFirst, source -> !(shulkerItem && ((ContainerRecord) source).type()
                            == dev.trashpanda.storageterminal.core.ContainerType.SHULKER_BOX));
            Set<ContainerKey> keys = new LinkedHashSet<>();
            for (PutPlanner.PutStop stop : stops) {
                ContainerKey key = ((ContainerRecord) stop.source()).key();
                keys.add(key);
                visitOrder.add(key);
            }
            planned.put(type.getKey(), keys);
        }
        if (visitOrder.isEmpty()) {
            showFeedback(minecraft, FeedbackKind.FAILED, "No free space in reach");
            return false;
        }
        List<ContainerDiscovery.Target> visits = new ArrayList<>();
        for (ContainerKey key : visitOrder) {
            visits.add(byKey.get(key));
        }
        jobs.enqueue(new PutJob(minecraft, cache, rateLimiter, config, requests, planned, visits, what, limit,
                result -> onPutFinished(minecraft, result), this::armLateOpenGuard));
        Map<ItemKey, Long> adding = new LinkedHashMap<>();
        for (PutJob.Request request : requests) {
            adding.merge(request.item(), (long) player.getInventory().getItem(request.inventoryIndex()).getCount(), Long::sum);
        }
        if (limit != PutOperator.WHOLE_STACKS) {
            adding.replaceAll((k, v) -> Math.min(v, limit));
        }
        setPrediction(adding);
        kickJobs();
        return true;
    }

    /**
     * One Shift + wheel notch: {@code take} moves one {@code item} from storage into the inventory
     * (onto {@code inventoryIndex} if it fits there), else one from the inventory into storage
     * (from {@code inventoryIndex}, or the smallest stack of the item when -1).
     */
    void wheel(Minecraft minecraft, ItemKey item, boolean take, int inventoryIndex) {
        if (wheelJobRunning && (runningTake() != null || runningPut() != null)) {
            if (item.equals(wheelItem)) {
                wheelQueued += take ? 1 : -1;
            } else {
                showFeedback(minecraft, FeedbackKind.FAILED, "Busy: wait for the current job to finish");
            }
            return;
        }
        startWheelJob(minecraft, item, take, 1, inventoryIndex);
    }

    private void startWheelJob(Minecraft minecraft, ItemKey item, boolean take, int count, int inventoryIndex) {
        wheelItem = item;
        wheelInventoryIndex = inventoryIndex;
        wheelQueued = 0;
        wheelFailed = false;
        boolean started;
        if (take) {
            started = startTake(minecraft, item, TakePlanner.Mode.ONE, count, inventoryIndex);
        } else {
            int from = storeSourceSlot(minecraft, item, inventoryIndex);
            if (from < 0) {
                ItemStack reference = ItemKeys.toStack(item, 1, minecraft.level.registryAccess());
                showFeedback(minecraft, FeedbackKind.FAILED, "No " + reference.getHoverName().getString() + " in your inventory");
                started = false;
            } else {
                started = startPut(minecraft, List.of(from), count);
            }
        }
        wheelJobRunning = started;
    }

    /** The given slot if it holds the item, else the smallest stack of it (main inventory before hotbar). */
    private int storeSourceSlot(Minecraft minecraft, ItemKey item, int inventoryIndex) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return -1;
        }
        if (inventoryIndex >= 0) {
            ItemStack stack = player.getInventory().getItem(inventoryIndex);
            return !stack.isEmpty() && ItemKeys.toKey(stack, minecraft.level.registryAccess()).equals(item)
                    ? inventoryIndex : -1;
        }
        int best = -1;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty() || !ItemKeys.toKey(stack, minecraft.level.registryAccess()).equals(item)) {
                continue;
            }
            int rank = stack.getCount() + (i < 9 ? 1000 : 0);
            if (rank < bestRank) {
                best = i;
                bestRank = rank;
            }
        }
        return best;
    }

    /** After a wheel job ends: do the notches that came in meanwhile, unless it went wrong. */
    private void continueWheel(Minecraft minecraft) {
        if (!wheelJobRunning || runningTake() != null || runningPut() != null) {
            return;
        }
        wheelJobRunning = false;
        int queued = wheelQueued;
        wheelQueued = 0;
        if (queued != 0 && !wheelFailed && wheelItem != null) {
            startWheelJob(minecraft, wheelItem, queued > 0, Math.abs(queued), wheelInventoryIndex);
        }
    }

    private void clearWheel() {
        wheelJobRunning = false;
        wheelQueued = 0;
        queued.clear();
    }

    /** True while anything is moving or waiting to move (the terminal then keeps its grid order). */
    boolean movesPending() {
        return runningTake() != null || runningPut() != null || !queued.isEmpty() || wheelJobRunning || cursorBusy();
    }

    private boolean idle() {
        return runningTake() == null && runningPut() == null;
    }

    /** Shift-click or Shift-drag over an inventory stack: store it now, or after the running job. */
    void queueStore(Minecraft minecraft, int inventoryIndex) {
        if (minecraft.player == null) {
            return;
        }
        ItemStack stack = minecraft.player.getInventory().getItem(inventoryIndex);
        if (stack.isEmpty()) {
            return;
        }
        if (idle() && queued.isEmpty()) {
            startPut(minecraft, List.of(inventoryIndex));
            return;
        }
        if (queued.peekLast() instanceof QueuedStore store) {
            store.slots().putIfAbsent(inventoryIndex, stack.copyWithCount(1));
        } else {
            Map<Integer, ItemStack> slots = new LinkedHashMap<>();
            slots.put(inventoryIndex, stack.copyWithCount(1));
            queued.addLast(new QueuedStore(slots));
        }
    }

    /** Shift-click or Shift-drag over a grid entry: take one stack now, or after the running job. */
    void queueTake(Minecraft minecraft, ItemKey item) {
        if (idle() && queued.isEmpty()) {
            startTake(minecraft, item, TakePlanner.Mode.STACK);
            return;
        }
        for (Queued q : queued) {
            if (q instanceof QueuedTake take && take.item().equals(item)) {
                return;
            }
        }
        queued.addLast(new QueuedTake(item));
    }

    /** Inventory slots waiting in the queue (dimmed like the ones being stored). */
    Set<Integer> queuedStoreSlots() {
        Set<Integer> slots = new HashSet<>();
        for (Queued q : queued) {
            if (q instanceof QueuedStore store) {
                slots.addAll(store.slots().keySet());
            }
        }
        return slots;
    }

    /** Items waiting in the queue to be taken (dimmed like the one being taken). */
    Set<ItemKey> queuedTakeItems() {
        Set<ItemKey> items = new HashSet<>();
        for (Queued q : queued) {
            if (q instanceof QueuedTake take) {
                items.add(take.item());
            }
        }
        return items;
    }

    /** Starts the next queued store or take once nothing else runs. */
    private void continueQueued(Minecraft minecraft) {
        while (!queued.isEmpty() && idle() && !wheelJobRunning && minecraft.player != null) {
            Queued next = queued.pollFirst();
            if (next instanceof QueuedStore store) {
                List<Integer> slots = new ArrayList<>();
                for (Map.Entry<Integer, ItemStack> e : store.slots().entrySet()) {
                    ItemStack now = minecraft.player.getInventory().getItem(e.getKey());
                    // Skip slots that changed to another item since they were dragged over.
                    if (!now.isEmpty() && ItemStack.isSameItemSameComponents(now, e.getValue())) {
                        slots.add(e.getKey());
                    }
                }
                if (!slots.isEmpty()) {
                    startPut(minecraft, slots);
                }
            } else if (next instanceof QueuedTake take) {
                startTake(minecraft, take.item(), TakePlanner.Mode.STACK);
            }
        }
    }

    private void onPutFinished(Minecraft minecraft, PutJob.Result result) {
        save();
        clearPrediction();
        storingHeld = 0;
        wheelFailed |= result.problem() != null;
        String stored = "Stored " + CountFormat.exact(result.moved());
        if (result.problem() == null) {
            showFeedback(minecraft, FeedbackKind.SUCCESS, stored + " " + result.what());
        } else if (result.moved() > 0) {
            showFeedback(minecraft, FeedbackKind.PARTIAL, stored + " of " + CountFormat.exact(result.wanted()) + " "
                    + result.what() + ": " + result.problem());
        } else {
            showFeedback(minecraft, FeedbackKind.FAILED, "Stored no " + result.what() + ": " + result.problem());
        }
    }

    /** Inventory slots for "Deposit all": the main inventory, plus the hotbar if configured. Never armor or offhand. */
    List<Integer> depositAllSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int i = config.depositIncludesHotbar() ? 0 : 9; i < 36; i++) {
            slots.add(i);
        }
        return slots;
    }

    /** The terminal closed: stop all work cleanly and save. */
    void onTerminalClosed() {
        clearWheel();
        heldSlot = -1;
        cursorTakeSlot = -1;
        cursorTakePreview = ItemStack.EMPTY;
        incomingSlot = -1;
        incomingPreview = ItemStack.EMPTY;
        storingHeld = 0;
        cursorOps.clear();
        clearPrediction();
        jobs.cancelAll("terminal closed");
        save();
    }

    Set<ContainerKey> reachable() {
        return reachable;
    }

    SortOrder sortOrder() {
        return sortOrder;
    }

    /** Changes the sort order and remembers it in the config file. */
    void setSortOrder(SortOrder order) {
        sortOrder = order;
        if (!order.name().equals(config.defaultSort())) {
            config = config.withDefaultSort(order);
            ConfigStore.save(config);
        }
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
        clearWheel();
        clearPrediction();
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
        ContainerSession session = activeSession();
        if (session != null) {
            session.onContentSynced(containerId);
        }
        if (passiveMenu != null && passiveMenu.containerId == containerId) {
            capturePassive();
        }
    }

    /** Returns true if the closed menu was ours, so vanilla must not close the current screen. */
    public boolean onServerClosedMenu(Minecraft minecraft, int containerId) {
        ContainerSession session = activeSession();
        return session != null && session.onServerClosed(minecraft, containerId);
    }

    // ---- internals -------------------------------------------------------------------

    private ContainerSession activeSessionWaiting() {
        ContainerSession session = activeSession();
        return session != null && session.isWaitingForMenu() ? session : null;
    }

    /** The container session of the running job (scan, take or put), if any. */
    ContainerSession activeSession() {
        return jobs.current() instanceof ContainerJob job ? job.activeSession() : null;
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
