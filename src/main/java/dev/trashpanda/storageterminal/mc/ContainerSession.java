// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import dev.trashpanda.storageterminal.StorageTerminal;
import dev.trashpanda.storageterminal.core.SlotStack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

/**
 * Opens one container without showing its screen, waits for the content sync, copies the
 * slots and closes it again (SPEC section 7: OPENING, AWAIT_SYNC, SNAPSHOT, CLOSING).
 * The cooldown between sessions is handled by the caller's rate limiter.
 */
final class ContainerSession {
    enum Phase {
        NOT_STARTED,
        AWAIT_SYNC,
        DONE,
        FAILED
    }

    private final ContainerDiscovery.Target target;
    private final long timeoutMs;
    private Phase phase = Phase.NOT_STARTED;
    private long startedAtMs;
    private AbstractContainerMenu menu;
    private boolean everClaimed;
    private boolean wrongMenu;
    private boolean synced;
    private List<SlotStack> snapshot;
    private String failure;

    ContainerSession(ContainerDiscovery.Target target, long timeoutMs) {
        this.target = target;
        this.timeoutMs = timeoutMs;
    }

    ContainerDiscovery.Target target() {
        return target;
    }

    Phase phase() {
        return phase;
    }

    List<SlotStack> snapshot() {
        return snapshot;
    }

    String failure() {
        return failure;
    }

    boolean isWaitingForMenu() {
        return phase == Phase.AWAIT_SYNC && menu == null && !everClaimed;
    }

    /** True if the server never opened a menu for us; it might still do so late. */
    boolean endedWithoutMenu() {
        return !everClaimed;
    }

    /** Sends the normal "use item on block" interaction for the target, as a click on its near face. */
    void start(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        startedAtMs = System.currentTimeMillis();
        phase = Phase.AWAIT_SYNC;
        minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                ContainerBlocks.hitToward(target.openPos(), player.getEyePosition()));
    }

    /**
     * Called from the open-screen hook while this session waits. Creates the menu without a
     * screen and makes it the player's current menu.
     */
    void claimMenu(Minecraft minecraft, MenuType<?> type, int containerId) {
        LocalPlayer player = minecraft.player;
        menu = type.create(containerId, player.getInventory());
        everClaimed = true;
        player.containerMenu = menu;
        wrongMenu = type != target.found().expectedMenu();
    }

    /** Called after the server sent the full contents of a container menu. */
    void onContentSynced(int containerId) {
        if (menu != null && menu.containerId == containerId) {
            synced = true;
        }
    }

    /** True if the server closed our menu; the caller then skips vanilla's close (which would close any screen). */
    boolean onServerClosed(Minecraft minecraft, int containerId) {
        if (menu == null || menu.containerId != containerId) {
            return false;
        }
        detachMenu(minecraft.player);
        if (phase == Phase.AWAIT_SYNC) {
            fail("the server closed it");
        }
        return true;
    }

    void tick(Minecraft minecraft) {
        if (phase != Phase.AWAIT_SYNC) {
            return;
        }
        if (wrongMenu) {
            close(minecraft);
            fail("unexpected menu type");
            return;
        }
        if (synced) {
            snapshot = copySlots(minecraft);
            close(minecraft);
            phase = Phase.DONE;
            return;
        }
        if (System.currentTimeMillis() - startedAtMs > timeoutMs) {
            close(minecraft);
            fail(menu == null ? "did not open" : "contents never arrived");
        }
    }

    /** Stops now and closes the menu if one is open. Safe to call in any phase. */
    void abort(Minecraft minecraft, String reason) {
        if (phase == Phase.DONE || phase == Phase.FAILED) {
            return;
        }
        close(minecraft);
        fail(reason);
    }

    private List<SlotStack> copySlots(Minecraft minecraft) {
        int count = target.found().slotCount();
        List<SlotStack> slots = new ArrayList<>(count);
        for (int i = 0; i < count && i < menu.slots.size(); i++) {
            slots.add(ItemKeys.toSlot(menu.slots.get(i).getItem(), minecraft.level.registryAccess()));
        }
        return slots;
    }

    /** Sends the normal container-close packet. Does not touch the current screen. */
    private void close(Minecraft minecraft) {
        if (menu == null) {
            return;
        }
        ClientPacketListener connection = minecraft.getConnection();
        if (connection != null) {
            connection.send(new ServerboundContainerClosePacket(menu.containerId));
        }
        if (minecraft.player != null) {
            detachMenu(minecraft.player);
        }
    }

    private void detachMenu(LocalPlayer player) {
        if (player.containerMenu == menu) {
            player.containerMenu = player.inventoryMenu;
        }
        menu = null;
    }

    private void fail(String reason) {
        failure = reason;
        phase = Phase.FAILED;
        StorageTerminal.LOGGER.debug("Session for {} failed: {}", target.key(), reason);
    }
}
