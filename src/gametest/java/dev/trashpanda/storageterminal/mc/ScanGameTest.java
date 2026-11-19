// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import dev.trashpanda.storageterminal.core.ContainerKey;
import dev.trashpanda.storageterminal.core.ContainerRecord;
import dev.trashpanda.storageterminal.core.ContainerState;
import dev.trashpanda.storageterminal.core.ContainerTag;
import dev.trashpanda.storageterminal.core.StorageIndex;
import dev.trashpanda.storageterminal.core.SortOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;

/**
 * M1 acceptance test (SPEC section 11) in a real client with an integrated server: a test room
 * with single, double and trapped chests, a barrel, a placed shulker box, a blocked chest, a chest
 * behind a wall, an ignored chest and one out of reach. Checks the scan shows no screen, the totals
 * match, ignored and out-of-reach containers are never opened, manual opens are cached, and the
 * cache survives leaving and rejoining the world.
 */
public final class ScanGameTest implements FabricClientGameTest {
    private BlockPos origin;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (TestWorldMaker.requested()) {
            return; // Only making the manual test world this run.
        }
        TestWorldSave save;
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            save = world.getWorldSave();
            world.getServer().runCommand("gamemode survival @a");
            world.getServer().runCommand("difficulty peaceful");
            origin = world.getServer().computeOnServer(server ->
                    server.getPlayerList().getPlayers().get(0).blockPosition());
            buildRoom(world);
            world.getConnection().waitForChunksDownload();
            context.waitTicks(10);

            // Ignore chest H before scanning (what the keybind does).
            BlockPos ignoredPos = at(3, 0, -1);
            context.runOnClient(mc -> {
                ContainerBlocks.Found h = ContainerBlocks.identify(mc.level, ignoredPos);
                check(h != null, "chest H exists on the client");
                StorageService.get().cache().toggleTag(h.key(), h.type(), h.positions(), ContainerTag.IGNORE);
            });

            // Scan, and watch every tick that no screen ever appears.
            AtomicReference<ScanJob.Summary> summary = new AtomicReference<>();
            AtomicBoolean screenShown = new AtomicBoolean(false);
            context.runOnClient(mc -> {
                String error = StorageService.get().startScan(mc, true, summary::set);
                check(error == null, "scan starts: " + error);
            });
            context.waitFor(mc -> {
                if (mc.gui.screen() != null && !(mc.gui.screen() instanceof ChatScreen)) {
                    screenShown.set(true);
                }
                return summary.get() != null;
            }, 600);
            ScanJob.Summary result = summary.get();
            check(result.abortReason() == null, "scan not aborted: " + result.abortReason());
            check(!screenShown.get(), "no vanilla screen during the scan");
            check(result.refreshed() == 6, "6 containers refreshed (A, B, C, D, E, G), got " + result.refreshed());
            check(result.unreachable() == 1, "1 unreachable (F), got " + result.unreachable());
            check(result.ignored() == 1, "1 ignored (H), got " + result.ignored());

            context.runOnClient(mc -> {
                check(mc.player.containerMenu == mc.player.inventoryMenu, "no container left open");
                Map<String, Long> totals = totals();
                expect(totals, "minecraft:dirt", 64);
                expect(totals, "minecraft:iron_ingot", 18);
                expect(totals, "minecraft:cobblestone", 96);
                expect(totals, "minecraft:gold_ingot", 7);
                expect(totals, "minecraft:diamond", 4);
                expect(totals, "minecraft:redstone", 20);
                expect(totals, "minecraft:emerald", 0);
                expect(totals, "minecraft:apple", 0);
                expect(totals, "minecraft:string", 0);

                ContainerRecord f = record(mc.level, at(0, 0, -2));
                check(f != null && f.state() == ContainerState.UNREACHABLE, "blocked chest F is unreachable");
                ContainerRecord h = record(mc.level, ignoredPos);
                check(h != null && h.isIgnored() && !h.hasSnapshot(), "ignored chest H was never opened");
                check(record(mc.level, at(8, 0, 0)) == null, "far chest I is unknown");
                ContainerRecord b = record(mc.level, at(0, 0, 2));
                check(b != null && b.positions().size() == 2 && b.slots().size() == 54, "double chest B is one 54-slot container");
            });

            // Passive capture: the player opens chest A by hand after it changed.
            world.getServer().runCommand(item(at(2, 0, 0), 5, "minecraft:gold_ingot", 3));
            context.waitTicks(5);
            BlockPos chestA = at(2, 0, 0);
            context.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                    ContainerBlocks.hitToward(chestA, mc.player.getEyePosition())));
            context.waitForScreen(ContainerScreen.class);
            context.waitTicks(5);
            context.runOnClient(mc -> mc.player.closeContainer());
            context.waitTicks(5);
            context.runOnClient(mc -> expect(totals(), "minecraft:gold_ingot", 10));
        }

        // Leaving the world saves; rejoining loads the same cache, marked stale.
        try (TestSingleplayerContext world = save.open()) {
            world.getConnection().waitForChunksDownload();
            context.waitTicks(5);
            context.runOnClient(mc -> {
                Map<String, Long> totals = totals();
                expect(totals, "minecraft:iron_ingot", 18);
                expect(totals, "minecraft:gold_ingot", 10);
                ContainerRecord a = record(mc.level, at(2, 0, 0));
                check(a != null && a.state() == ContainerState.STALE, "reloaded records are stale");
                ContainerRecord h = record(mc.level, at(3, 0, -1));
                check(h != null && h.isIgnored(), "ignore tag survives a restart");
            });
            terminalChecks(context, world);
            takeChecks(context, world);
            depositChecks(context, world);
        }
    }

    /**
     * M4: storing items. Partial stacks first, then same item, then free space; ignored containers
     * never used; hotbar kept by Deposit all; full storage reported; every item counted on the server.
     */
    private void depositChecks(ClientGameTestContext context, TestSingleplayerContext world) {
        context.getInput().pressKey(StorageTerminalClient.openTerminalKey());
        context.waitForScreen(TerminalScreen.class);
        context.waitFor(mc -> StorageService.get().runningScan() == null, 600);
        BlockPos chestC = at(-2, 0, 0);
        BlockPos chestH = at(3, 0, -1);

        // Gold: trapped chest C already has a partial gold stack, so the 5 gold go there.
        long goldTotal = serverCount(world, "minecraft:gold_ingot", false);
        long goldInC = serverCountAt(world, chestC, "minecraft:gold_ingot");
        clickInventory(context, "minecraft:gold_ingot", Mod.NONE);
        context.takeScreenshot("m4-storing");
        waitForPut(context);
        check(serverCount(world, "minecraft:gold_ingot", true) == 0, "gold left the inventory");
        check(serverCountAt(world, chestC, "minecraft:gold_ingot") == goldInC + 5, "gold joined the partial stack in C");
        check(serverCount(world, "minecraft:gold_ingot", false) == goldTotal, "gold neither lost nor duplicated");
        expectFeedback(context, StorageService.FeedbackKind.SUCCESS);

        // Iron: no container holds iron, so it goes to free space, never the ignored chest H.
        long ironTotal = serverCount(world, "minecraft:iron_ingot", false);
        clickInventory(context, "minecraft:iron_ingot", Mod.NONE);
        waitForPut(context);
        check(serverCount(world, "minecraft:iron_ingot", true) == 0, "iron left the inventory");
        check(serverCount(world, "minecraft:iron_ingot", false) == ironTotal, "iron neither lost nor duplicated");
        check(serverCountAt(world, chestH, "minecraft:iron_ingot") == 0, "ignored chest H not used");

        // Shift + wheel: one gold per notch. Two down over the grid entry (the second arrives while the
        // first job runs and is queued), then one up over the inventory stack.
        long goldNow = serverCount(world, "minecraft:gold_ingot", false);
        int[] goldEntry = context.computeOnClient(mc -> {
            List<StorageIndex.Entry> shown = ((TerminalScreen) mc.gui.screen()).shownEntries();
            for (int i = 0; i < shown.size(); i++) {
                if (shown.get(i).item().itemId().equals("minecraft:gold_ingot")) {
                    return ((TerminalScreen) mc.gui.screen()).gridSlotCenter(i);
                }
            }
            return null;
        });
        check(goldEntry != null, "gold is shown in the terminal");
        shiftScrollAt(context, goldEntry, -1);
        shiftScrollAt(context, goldEntry, -1);
        waitForJobs(context);
        check(serverCount(world, "minecraft:gold_ingot", true) == 2, "2 gold taken by Shift + wheel, got "
                + serverCount(world, "minecraft:gold_ingot", true));
        int goldSlot = inventoryIndexOf(context, "minecraft:gold_ingot");
        int[] goldStack = context.computeOnClient(mc -> ((TerminalScreen) mc.gui.screen()).inventorySlotCenter(goldSlot));
        shiftScrollAt(context, goldStack, 1);
        waitForJobs(context);
        check(serverCount(world, "minecraft:gold_ingot", true) == 1, "1 gold stored by Shift + wheel, 1 left, got "
                + serverCount(world, "minecraft:gold_ingot", true));
        check(serverCount(world, "minecraft:gold_ingot", false) == goldNow, "gold neither lost nor duplicated (wheel)");
        context.runOnClient(mc -> check(mc.player.containerMenu.getCarried().isEmpty(), "cursor empty after wheel"));

        // Shift-click stores one stack only; Alt-click then stores every cobblestone stack.
        world.getServer().runCommand("give @a minecraft:cobblestone 100");
        context.waitTicks(3);
        long cobbleTotal = serverCount(world, "minecraft:cobblestone", false);
        check(cobbleTotal == 132, "132 cobblestone before storing, got " + cobbleTotal);
        long cobbleHeld = serverCount(world, "minecraft:cobblestone", true);
        int firstCobble = inventoryIndexOf(context, "minecraft:cobblestone");
        int firstCobbleCount = context.computeOnClient(mc -> mc.player.getInventory().getItem(firstCobble).getCount());
        clickInventory(context, "minecraft:cobblestone", Mod.SHIFT);
        waitForPut(context);
        check(serverCount(world, "minecraft:cobblestone", true) == cobbleHeld - firstCobbleCount,
                "Shift-click stored just one stack");
        clickInventory(context, "minecraft:cobblestone", Mod.ALT);
        waitForPut(context);
        check(serverCount(world, "minecraft:cobblestone", true) == 0, "all cobblestone stacks stored");
        check(serverCount(world, "minecraft:cobblestone", false) == cobbleTotal, "cobblestone neither lost nor duplicated");

        // Deposit all: main inventory only, the hotbar stays.
        world.getServer().runCommand("item replace entity @a inventory.5 with minecraft:string 7");
        world.getServer().runCommand("item replace entity @a inventory.6 with minecraft:flint 3");
        world.getServer().runCommand("item replace entity @a hotbar.4 with minecraft:torch 16");
        context.waitTicks(3);
        long stringTotal = serverCount(world, "minecraft:string", false);
        clickDepositAll(context);
        waitForPut(context);
        check(serverCount(world, "minecraft:string", true) == 0 && serverCount(world, "minecraft:flint", true) == 0,
                "main inventory stored");
        check(serverCount(world, "minecraft:torch", true) == 16, "hotbar kept");
        check(serverCount(world, "minecraft:string", false) == stringTotal, "string neither lost nor duplicated");
        check(serverCount(world, "minecraft:flint", false) == 3, "flint neither lost nor duplicated");
        context.takeScreenshot("m4-deposit-all");

        // Full storage. Fill every usable container behind the cache's back: the job finds no room on the spot.
        for (int[] d : new int[][] {{2, 0, 0}, {0, 0, 2}, {1, 0, 2}, {-2, 0, 0}, {-2, 0, 2}, {2, 0, 2}, {-1, 0, -3}}) {
            BlockPos pos = at(d[0], d[1], d[2]);
            for (int slot = 0; slot < 27; slot++) {
                world.getServer().runCommand(item(pos, slot, "minecraft:stone", 64));
            }
        }
        world.getServer().runCommand("give @a minecraft:dirt 10");
        context.waitTicks(3);
        long applesInH = serverCountAt(world, chestH, "minecraft:apple");
        clickInventory(context, "minecraft:dirt", Mod.NONE);
        waitForPut(context);
        check(serverCount(world, "minecraft:dirt", true) == 10, "dirt still in the inventory");
        check(serverCountAt(world, chestH, "minecraft:dirt") == 0, "ignored chest H not used, even when all else is full");
        expectFeedback(context, StorageService.FeedbackKind.FAILED);
        context.takeScreenshot("m4-no-space");

        // After a rescan the cache knows it is full: refused before opening anything.
        context.runOnClient(mc -> StorageService.get().startTerminalScan(mc, true));
        context.waitFor(mc -> StorageService.get().runningScan() == null, 600);
        clickInventory(context, "minecraft:dirt", Mod.NONE);
        context.waitTicks(2);
        context.runOnClient(mc -> check(StorageService.get().runningPut() == null, "nothing to visit"));
        expectFeedback(context, StorageService.FeedbackKind.FAILED);
        check(serverCount(world, "minecraft:dirt", true) == 10, "dirt still in the inventory");
        check(serverCountAt(world, chestH, "minecraft:apple") == applesInH && serverCountAt(world, chestH, "minecraft:dirt") == 0,
                "ignored chest H unchanged");
        context.runOnClient(mc -> mc.gui.setScreen(null));
    }

    /** Modifier key held during a test click. */
    private enum Mod {
        NONE,
        SHIFT,
        ALT
    }

    private static void holdMod(ClientGameTestContext context, Mod mod) {
        switch (mod) {
            case SHIFT -> context.getInput().holdShift();
            case ALT -> context.getInput().holdAlt();
            default -> {
            }
        }
    }

    private static void releaseMod(ClientGameTestContext context, Mod mod) {
        switch (mod) {
            case SHIFT -> context.getInput().releaseShift();
            case ALT -> context.getInput().releaseAlt();
            default -> {
            }
        }
    }

    private void clickInventory(ClientGameTestContext context, String itemId, Mod mod) {
        int index = inventoryIndexOf(context, itemId);
        double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
        int[] center = context.computeOnClient(mc -> ((TerminalScreen) mc.gui.screen()).inventorySlotCenter(index));
        context.getInput().setCursorPos(center[0] * scale, center[1] * scale);
        context.waitTick();
        holdMod(context, mod);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        releaseMod(context, mod);
        context.waitTick();
    }

    private int inventoryIndexOf(ClientGameTestContext context, String itemId) {
        int index = context.computeOnClient(mc -> {
            for (int i = 0; i < 36; i++) {
                if (countIf(mc.player.getInventory().getItem(i), itemId) > 0) {
                    return i;
                }
            }
            return -1;
        });
        check(index >= 0, itemId + " is in the inventory");
        return index;
    }

    /** Shift + wheel at a GUI position: positive {@code amount} is wheel up. */
    private static void shiftScrollAt(ClientGameTestContext context, int[] guiPos, double amount) {
        double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
        context.getInput().setCursorPos(guiPos[0] * scale, guiPos[1] * scale);
        context.waitTick();
        context.getInput().holdShift();
        context.getInput().scroll(amount);
        context.getInput().releaseShift();
        context.waitTick();
    }

    private static void waitForJobs(ClientGameTestContext context) {
        context.waitFor(mc -> StorageService.get().runningTake() == null && StorageService.get().runningPut() == null, 600);
        context.waitTicks(5);
    }

    private void clickDepositAll(ClientGameTestContext context) {
        double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
        int[] center = context.computeOnClient(mc -> ((TerminalScreen) mc.gui.screen()).depositAllCenter());
        context.getInput().setCursorPos(center[0] * scale, center[1] * scale);
        context.waitTick();
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTick();
    }

    private static void waitForPut(ClientGameTestContext context) {
        context.waitFor(mc -> StorageService.get().runningPut() == null, 600);
        context.waitTicks(5);
    }

    private long serverCountAt(TestSingleplayerContext world, BlockPos pos, String itemId) {
        return world.getServer().computeOnServer(server -> {
            long n = 0;
            if (server.overworld().getBlockEntity(pos) instanceof net.minecraft.world.Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    n += countIf(c.getItem(i), itemId);
                }
            }
            return n;
        });
    }

    /**
     * M3: taking items. Every check also counts the item on the server (all room containers, the
     * player's inventory and cursor) before and after, so nothing can be lost or duplicated unseen.
     */
    private void takeChecks(ClientGameTestContext context, TestSingleplayerContext world) {
        context.getInput().pressKey(StorageTerminalClient.openTerminalKey());
        context.waitForScreen(TerminalScreen.class);
        context.waitFor(mc -> StorageService.get().runningScan() == null, 600);

        // Left click: one stack of iron = all 18, spread over chest A (10), double chest B (5), barrel D (3).
        long ironTotal = serverCount(world, "minecraft:iron_ingot", false);
        clickEntry(context, "minecraft:iron_ingot", InputConstants.MOUSE_BUTTON_LEFT, Mod.NONE);
        context.takeScreenshot("m3-taking");
        waitForTake(context);
        check(serverCount(world, "minecraft:iron_ingot", true) == 18, "18 iron in the inventory, got "
                + serverCount(world, "minecraft:iron_ingot", true));
        check(serverCount(world, "minecraft:iron_ingot", false) == ironTotal, "iron neither lost nor duplicated");
        expectFeedback(context, StorageService.FeedbackKind.SUCCESS);
        context.takeScreenshot("m3-took-iron");

        // Right click: half a stack of gold = 5 of 10 (A: 3, C: 7), so one stack is split.
        long goldTotal = serverCount(world, "minecraft:gold_ingot", false);
        clickEntry(context, "minecraft:gold_ingot", InputConstants.MOUSE_BUTTON_RIGHT, Mod.NONE);
        waitForTake(context);
        check(serverCount(world, "minecraft:gold_ingot", true) == 5, "5 gold in the inventory, got "
                + serverCount(world, "minecraft:gold_ingot", true));
        check(serverCount(world, "minecraft:gold_ingot", false) == goldTotal, "gold neither lost nor duplicated");

        // Re-verify: someone empties half of double chest B after the scan. The cache still says 96.
        world.getServer().runCommand("item replace block " + at(0, 0, 2).getX() + " " + at(0, 0, 2).getY() + " "
                + at(0, 0, 2).getZ() + " container.0 with minecraft:air");
        context.waitTicks(3);
        clickEntry(context, "minecraft:cobblestone", InputConstants.MOUSE_BUTTON_LEFT, Mod.ALT);
        waitForTake(context);
        check(serverCount(world, "minecraft:cobblestone", true) == 32, "took the 32 that are really there, got "
                + serverCount(world, "minecraft:cobblestone", true));
        check(serverCount(world, "minecraft:cobblestone", false) == 32, "cobblestone neither lost nor duplicated");
        expectFeedback(context, StorageService.FeedbackKind.PARTIAL);
        context.takeScreenshot("m3-partial");
        context.runOnClient(mc -> check(entryTotal((TerminalScreen) mc.gui.screen(), "minecraft:cobblestone") == 0,
                "cache corrected after the take"));

        // Full inventory: nothing moves, the player is told.
        world.getServer().runCommand("give @a minecraft:stone 2112");
        context.waitTicks(3);
        clickEntry(context, "minecraft:diamond", InputConstants.MOUSE_BUTTON_LEFT, Mod.NONE);
        context.waitTicks(3);
        waitForTake(context);
        check(serverCount(world, "minecraft:diamond", true) == 0, "no diamond taken into a full inventory");
        check(serverCount(world, "minecraft:diamond", false) == 4, "diamonds all still there");
        expectFeedback(context, StorageService.FeedbackKind.FAILED);
        world.getServer().runCommand("clear @a minecraft:stone");
        context.waitTicks(3);

        // Closing the terminal right after a click: the take stops cleanly, nothing left open or on the cursor.
        long redstoneTotal = serverCount(world, "minecraft:redstone", false);
        clickEntry(context, "minecraft:redstone", InputConstants.MOUSE_BUTTON_LEFT, Mod.NONE);
        context.runOnClient(mc -> mc.gui.setScreen(null));
        context.waitTicks(40);
        context.runOnClient(mc -> {
            check(StorageService.get().runningTake() == null, "take stopped");
            check(mc.player.containerMenu == mc.player.inventoryMenu, "client has no container open");
            check(mc.player.containerMenu.getCarried().isEmpty(), "client cursor empty");
            check(!(mc.gui.screen() instanceof ContainerScreen), "no container screen");
        });
        boolean serverClean = world.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().get(0);
            return player.containerMenu == player.inventoryMenu && player.containerMenu.getCarried().isEmpty();
        });
        check(serverClean, "server: no container open, cursor empty");
        check(serverCount(world, "minecraft:redstone", false) == redstoneTotal, "redstone neither lost nor duplicated");
    }

    private void clickEntry(ClientGameTestContext context, String itemId, int button, Mod mod) {
        int index = context.computeOnClient(mc -> {
            List<StorageIndex.Entry> shown = ((TerminalScreen) mc.gui.screen()).shownEntries();
            for (int i = 0; i < shown.size(); i++) {
                if (shown.get(i).item().itemId().equals(itemId)) {
                    return i;
                }
            }
            return -1;
        });
        check(index >= 0, itemId + " is shown in the terminal");
        double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
        int[] center = context.computeOnClient(mc -> ((TerminalScreen) mc.gui.screen()).gridSlotCenter(index));
        context.getInput().setCursorPos(center[0] * scale, center[1] * scale);
        context.waitTick();
        holdMod(context, mod);
        context.getInput().pressMouse(button);
        releaseMod(context, mod);
        context.waitTick();
    }

    private static void waitForTake(ClientGameTestContext context) {
        context.waitFor(mc -> StorageService.get().runningTake() == null, 400);
        context.waitTicks(5);
    }

    private static void expectFeedback(ClientGameTestContext context, StorageService.FeedbackKind kind) {
        context.runOnClient(mc -> {
            StorageService.Feedback feedback = StorageService.get().feedback();
            check(feedback != null && feedback.kind() == kind,
                    "expected " + kind + " feedback, got " + (feedback == null ? "none" : feedback.kind() + ": " + feedback.text()));
        });
    }

    /** Server truth: the item in all room containers plus the player (inventory and cursor), or the player only. */
    private long serverCount(TestSingleplayerContext world, String itemId, boolean playerOnly) {
        int[][] room = {{2, 0, 0}, {0, 0, 2}, {1, 0, 2}, {-2, 0, 0}, {-2, 0, 2}, {2, 0, 2}, {0, 0, -2}, {-1, 0, -3},
                {3, 0, -1}, {8, 0, 0}};
        return world.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().get(0);
            long n = 0;
            if (!playerOnly) {
                var level = server.overworld();
                for (int[] d : room) {
                    if (level.getBlockEntity(at(d[0], d[1], d[2])) instanceof net.minecraft.world.Container c) {
                        for (int i = 0; i < c.getContainerSize(); i++) {
                            n += countIf(c.getItem(i), itemId);
                        }
                    }
                }
                n += countIf(player.containerMenu.getCarried(), itemId);
            }
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                n += countIf(player.getInventory().getItem(i), itemId);
            }
            return n;
        });
    }

    private static long countIf(net.minecraft.world.item.ItemStack stack, String itemId) {
        return !stack.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                .toString().equals(itemId) ? stack.getCount() : 0;
    }

    /** M2: the terminal opens from the cache, refreshes live, searches, sorts, and stops cleanly when closed. */
    private void terminalChecks(ClientGameTestContext context, TestSingleplayerContext world) {
        // Open with the real key; contents show at once from the (stale) cache.
        context.getInput().pressKey(StorageTerminalClient.openTerminalKey());
        context.waitForScreen(TerminalScreen.class);
        context.runOnClient(mc -> {
            TerminalScreen screen = (TerminalScreen) mc.gui.screen();
            check(entryTotal(screen, "minecraft:iron_ingot") == 18, "terminal shows cached contents at once");
        });
        context.takeScreenshot("m2-terminal-opening");

        // Background refresh finishes; nothing is stale any more and totals still match.
        context.waitFor(mc -> StorageService.get().runningScan() == null, 600);
        context.runOnClient(mc -> {
            TerminalScreen screen = (TerminalScreen) mc.gui.screen();
            check(screen.shownEntries().stream().noneMatch(StorageIndex.Entry::anyStale), "all rescanned");
            check(entryTotal(screen, "minecraft:iron_ingot") == 18, "iron 18");
            check(entryTotal(screen, "minecraft:gold_ingot") == 10, "gold 10");
            check(entryTotal(screen, "minecraft:cobblestone") == 96, "cobblestone 96");
            check(entryTotal(screen, "minecraft:diamond") == 4, "diamond 4");
            check(entryTotal(screen, "minecraft:redstone") == 20, "redstone 20");
            check(entryTotal(screen, "minecraft:dirt") == 64, "dirt 64");
            check(entryTotal(screen, "minecraft:apple") == 0, "ignored chest not shown");
            check(entryTotal(screen, "minecraft:emerald") == 0, "blocked chest not shown");
            check(entryTotal(screen, "minecraft:string") == 0, "out-of-reach chest not shown");
            check(screen.shownEntries().get(0).item().itemId().equals("minecraft:cobblestone"), "sorted by count");
            check(screen.searchText().isEmpty(), "search starts empty");
        });

        // Hover an entry for a tooltip screenshot.
        double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
        int[] center = context.computeOnClient(mc -> ((TerminalScreen) mc.gui.screen()).gridSlotCenter(1));
        context.getInput().setCursorPos(center[0] * scale, center[1] * scale);
        context.waitTicks(3);
        context.takeScreenshot("m2-terminal-tooltip");

        // Search: typed into the focused search box.
        context.getInput().typeChars("iron");
        context.waitTicks(2);
        context.runOnClient(mc -> {
            TerminalScreen screen = (TerminalScreen) mc.gui.screen();
            check(screen.searchText().equals("iron"), "typing goes to the search box, got: " + screen.searchText());
            check(screen.shownEntries().size() == 1, "search iron finds 1 entry, got " + screen.shownEntries().size());
            screen.setSearch("#c:ingots");
            List<String> ids = screen.shownEntries().stream().map(e -> e.item().itemId()).toList();
            check(ids.contains("minecraft:iron_ingot") && ids.contains("minecraft:gold_ingot") && ids.size() == 2,
                    "#c:ingots finds iron and gold, got " + ids);
            screen.setSearch("@minecraft");
            check(screen.shownEntries().size() == 6, "@minecraft finds all 6");
            screen.setSearch("diamon sword");
        });
        context.waitTicks(2);
        context.takeScreenshot("m2-terminal-no-results");
        context.runOnClient(mc -> {
            TerminalScreen screen = (TerminalScreen) mc.gui.screen();
            check(screen.shownEntries().isEmpty(), "no results");
            screen.setSearch("");
            StorageService.get().setSortOrder(SortOrder.NAME);
            check(screen.shownEntries().get(0).item().itemId().equals("minecraft:cobblestone"), "name sort: Cobblestone first");
            StorageService.get().setSortOrder(SortOrder.COUNT);
        });

        // Small window: fewer rows, still works.
        context.getInput().resizeWindow(640, 360);
        context.waitTicks(5);
        context.takeScreenshot("m2-terminal-small-window");
        context.getInput().resizeWindow(1280, 720);
        context.waitTicks(5);

        // Closing mid-scan leaves no container open, on the client or the server.
        context.runOnClient(mc -> StorageService.get().startTerminalScan(mc, true));
        context.waitFor(mc -> {
            ScanJob scan = StorageService.get().runningScan();
            return scan != null && scan.activeSession() != null;
        }, 200);
        context.runOnClient(mc -> mc.gui.setScreen(null));
        AtomicBoolean containerScreen = new AtomicBoolean(false);
        for (int i = 0; i < 40; i++) {
            context.waitTick();
            context.runOnClient(mc -> {
                if (mc.gui.screen() instanceof ContainerScreen) {
                    containerScreen.set(true);
                }
            });
        }
        check(!containerScreen.get(), "no container screen after closing mid-scan");
        context.runOnClient(mc -> {
            check(StorageService.get().runningScan() == null, "scan stopped");
            check(mc.player.containerMenu == mc.player.inventoryMenu, "client has no container open");
        });
        boolean serverClean = world.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().get(0);
            return player.containerMenu == player.inventoryMenu;
        });
        check(serverClean, "server has no container open for the player");
    }

    private static long entryTotal(TerminalScreen screen, String id) {
        return screen.shownEntries().stream().filter(e -> e.item().itemId().equals(id))
                .mapToLong(StorageIndex.Entry::total).sum();
    }

    private void buildRoom(TestSingleplayerContext world) {
        var server = world.getServer();
        // A: single chest
        server.runCommand(setblock(at(2, 0, 0), "minecraft:chest[facing=west]"));
        server.runCommand(item(at(2, 0, 0), 0, "minecraft:dirt", 64));
        server.runCommand(item(at(2, 0, 0), 1, "minecraft:iron_ingot", 10));
        // B: double chest (left half connects east when facing north)
        server.runCommand(setblock(at(0, 0, 2), "minecraft:chest[facing=north,type=left]"));
        server.runCommand(setblock(at(1, 0, 2), "minecraft:chest[facing=north,type=right]"));
        server.runCommand(item(at(0, 0, 2), 0, "minecraft:cobblestone", 64));
        server.runCommand(item(at(1, 0, 2), 3, "minecraft:cobblestone", 32));
        server.runCommand(item(at(1, 0, 2), 4, "minecraft:iron_ingot", 5));
        // C: trapped chest
        server.runCommand(setblock(at(-2, 0, 0), "minecraft:trapped_chest[facing=east]"));
        server.runCommand(item(at(-2, 0, 0), 0, "minecraft:gold_ingot", 7));
        // D: barrel
        server.runCommand(setblock(at(-2, 0, 2), "minecraft:barrel[facing=up]"));
        server.runCommand(item(at(-2, 0, 2), 0, "minecraft:iron_ingot", 3));
        // E: placed shulker box
        server.runCommand(setblock(at(2, 0, 2), "minecraft:shulker_box[facing=up]"));
        server.runCommand(item(at(2, 0, 2), 0, "minecraft:diamond", 4));
        // F: chest with a solid block on top (cannot open)
        server.runCommand(setblock(at(0, 0, -2), "minecraft:chest[facing=south]"));
        server.runCommand(setblock(at(0, 1, -2), "minecraft:stone"));
        server.runCommand(item(at(0, 0, -2), 0, "minecraft:emerald", 9));
        // G: chest behind a wall (no line of sight)
        server.runCommand(setblock(at(-1, 0, -3), "minecraft:chest[facing=south]"));
        server.runCommand(setblock(at(-1, 0, -2), "minecraft:stone"));
        server.runCommand(setblock(at(-1, 1, -2), "minecraft:stone"));
        server.runCommand(item(at(-1, 0, -3), 0, "minecraft:redstone", 20));
        // H: chest the player ignores
        server.runCommand(setblock(at(3, 0, -1), "minecraft:chest[facing=west]"));
        server.runCommand(item(at(3, 0, -1), 0, "minecraft:apple", 11));
        // I: chest out of reach
        server.runCommand(setblock(at(8, 0, 0), "minecraft:chest[facing=west]"));
        server.runCommand(item(at(8, 0, 0), 0, "minecraft:string", 5));
    }

    private BlockPos at(int dx, int dy, int dz) {
        return origin.offset(dx, dy, dz);
    }

    private static String setblock(BlockPos pos, String block) {
        return "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block;
    }

    private static String item(BlockPos pos, int slot, String id, int count) {
        return "item replace block " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                + " container." + slot + " with " + id + " " + count;
    }

    private static ContainerRecord record(net.minecraft.world.level.Level level, BlockPos pos) {
        ContainerBlocks.Found found = ContainerBlocks.identify(level, pos);
        if (found == null) {
            return null;
        }
        ContainerKey key = found.key();
        return StorageService.get().cache().get(key);
    }

    private static Map<String, Long> totals() {
        Map<String, Long> totals = new HashMap<>();
        for (StorageIndex.Entry entry : StorageIndex.aggregate(StorageService.get().cache().records())) {
            totals.merge(entry.item().itemId(), entry.total(), Long::sum);
        }
        return totals;
    }

    private static void expect(Map<String, Long> totals, String id, long count) {
        long actual = totals.getOrDefault(id, 0L);
        check(actual == count, id + ": expected " + count + ", got " + actual);
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("Check failed: " + what);
        }
    }
}
