// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import com.mojang.blaze3d.platform.InputConstants;
import dev.trashpanda.storageterminal.core.StorageIndex;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * M5: the whole flow against a dedicated server over a real connection (the mod is client-only,
 * the server has nothing of it). Scan, take everything, store it back; the server's counts must
 * match at every step and no container may stay open.
 */
public final class MultiplayerGameTest implements FabricClientGameTest {
    private static final String IRON = "minecraft:iron_ingot";
    private BlockPos origin;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (TestWorldMaker.requested()) {
            return; // Only making the manual test world this run.
        }
        try (TestDedicatedServerContext server = context.worldBuilder().createServer();
                TestDedicatedServerConnection connection = server.connect()) {
            server.runCommand("difficulty peaceful");
            server.runCommand("time set day");
            server.runCommand("gamemode survival @a");
            origin = server.computeOnServer(s -> s.getPlayerList().getPlayers().get(0).blockPosition());
            server.runCommand(setblock(at(2, 0, 0), "minecraft:chest[facing=west]"));
            server.runCommand(item(at(2, 0, 0), 0, IRON, 30));
            server.runCommand(item(at(2, 0, 0), 5, IRON, 10));
            server.runCommand(setblock(at(-2, 0, 0), "minecraft:barrel[facing=up]"));
            server.runCommand(item(at(-2, 0, 0), 0, IRON, 50));
            server.runCommand(setblock(at(0, 0, 2), "minecraft:chest[facing=north]"));
            connection.waitForChunksDownload();
            context.waitTicks(20);

            context.getInput().pressKey(StorageTerminalClient.openTerminalKey());
            context.waitForScreen(TerminalScreen.class);
            context.waitFor(mc -> StorageService.get().runningScan() == null, 600);
            context.runOnClient(mc -> check(total((TerminalScreen) mc.gui.screen(), IRON) == 90, "terminal shows 90 iron"));
            context.takeScreenshot("m5-multiplayer-terminal");

            // Take everything (Alt-click), then store it all back (Alt-click in the inventory).
            clickEntry(context, IRON);
            context.waitFor(mc -> StorageService.get().runningTake() == null, 600);
            context.waitTicks(10);
            check(count(server, IRON, true) == 90, "90 iron in the inventory, got " + count(server, IRON, true));
            check(count(server, IRON, false) == 90, "iron neither lost nor duplicated");

            clickInventory(context, IRON);
            context.waitFor(mc -> StorageService.get().runningPut() == null, 600);
            context.waitTicks(10);
            check(count(server, IRON, true) == 0, "all iron stored again, " + count(server, IRON, true) + " left");
            check(count(server, IRON, false) == 90, "iron neither lost nor duplicated");
            context.takeScreenshot("m5-multiplayer-stored");

            // Shift + wheel: one down takes 1 iron, one up stores it again (pick up, right-click, put back).
            scrollEntry(context, IRON, -1);
            waitForJobs(context);
            check(count(server, IRON, true) == 1, "1 iron taken by Shift + wheel, got " + count(server, IRON, true));
            scrollEntry(context, IRON, 1);
            waitForJobs(context);
            check(count(server, IRON, true) == 0, "the iron stored again by Shift + wheel");
            check(count(server, IRON, false) == 90, "iron neither lost nor duplicated");

            context.runOnClient(mc -> mc.gui.setScreen(null));
            context.waitTicks(10);
            boolean clean = server.computeOnServer(s -> {
                var player = s.getPlayerList().getPlayers().get(0);
                return player.containerMenu == player.inventoryMenu && player.containerMenu.getCarried().isEmpty();
            });
            check(clean, "server: no container open, cursor empty");
        }
    }

    private void clickEntry(ClientGameTestContext context, String itemId) {
        altClick(context, entryCenter(context, itemId));
    }

    private void scrollEntry(ClientGameTestContext context, String itemId, double amount) {
        int[] c = entryCenter(context, itemId);
        double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
        context.getInput().setCursorPos(c[0] * scale, c[1] * scale);
        context.waitTick();
        context.getInput().holdShift();
        context.getInput().scroll(amount);
        context.getInput().releaseShift();
        context.waitTick();
    }

    private static void waitForJobs(ClientGameTestContext context) {
        context.waitFor(mc -> StorageService.get().runningTake() == null && StorageService.get().runningPut() == null, 600);
        context.waitTicks(10);
    }

    private int[] entryCenter(ClientGameTestContext context, String itemId) {
        int index = context.computeOnClient(mc -> {
            List<StorageIndex.Entry> shown = ((TerminalScreen) mc.gui.screen()).shownEntries();
            for (int i = 0; i < shown.size(); i++) {
                if (shown.get(i).item().itemId().equals(itemId)) {
                    return i;
                }
            }
            return -1;
        });
        check(index >= 0, itemId + " shown");
        return context.computeOnClient(mc -> ((TerminalScreen) mc.gui.screen()).gridSlotCenter(index));
    }

    private void clickInventory(ClientGameTestContext context, String itemId) {
        int index = context.computeOnClient(mc -> {
            for (int i = 0; i < 36; i++) {
                if (matches(mc.player.getInventory().getItem(i), itemId)) {
                    return i;
                }
            }
            return -1;
        });
        check(index >= 0, itemId + " in the inventory");
        int[] c = context.computeOnClient(mc -> ((TerminalScreen) mc.gui.screen()).inventorySlotCenter(index));
        altClick(context, c);
    }

    private static void altClick(ClientGameTestContext context, int[] guiPos) {
        double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
        context.getInput().setCursorPos(guiPos[0] * scale, guiPos[1] * scale);
        context.waitTick();
        context.getInput().holdAlt();
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.getInput().releaseAlt();
        context.waitTick();
    }

    /** Server truth: the item in the test containers plus the player, or the player only. */
    private long count(TestDedicatedServerContext server, String itemId, boolean playerOnly) {
        return server.computeOnServer(s -> {
            var player = s.getPlayerList().getPlayers().get(0);
            long n = 0;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                n += amount(player.getInventory().getItem(i), itemId);
            }
            if (!playerOnly) {
                n += amount(player.containerMenu.getCarried(), itemId);
                for (BlockPos pos : List.of(at(2, 0, 0), at(-2, 0, 0), at(0, 0, 2))) {
                    if (s.overworld().getBlockEntity(pos) instanceof Container c) {
                        for (int i = 0; i < c.getContainerSize(); i++) {
                            n += amount(c.getItem(i), itemId);
                        }
                    }
                }
            }
            return n;
        });
    }

    private static long total(TerminalScreen screen, String itemId) {
        return screen.shownEntries().stream().filter(e -> e.item().itemId().equals(itemId))
                .mapToLong(StorageIndex.Entry::total).sum();
    }

    private static boolean matches(ItemStack stack, String itemId) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId);
    }

    private static long amount(ItemStack stack, String itemId) {
        return matches(stack, itemId) ? stack.getCount() : 0;
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

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("Check failed: " + what);
        }
    }
}
