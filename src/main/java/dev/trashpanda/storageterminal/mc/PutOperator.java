// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import dev.trashpanda.storageterminal.StorageTerminal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Moves the given inventory stacks into one open container with shift-clicks (vanilla fills
 * matching stacks in the container first, then empty slots). Whatever does not fit stays in
 * the inventory for the next container. Clicks are spread over ticks; the result is read back
 * after the server had time to correct anything.
 */
final class PutOperator implements ContainerSession.Operator {
    private static final int MAX_CLICKS_PER_TICK = 6;

    /** Inventory index (0-35) to move, and what it held when planned. */
    private final Deque<Map.Entry<Integer, ItemStack>> pending = new ArrayDeque<>();
    private int settleTicks = -1;
    private boolean finished;

    PutOperator(Map<Integer, ItemStack> inventorySlots) {
        pending.addAll(inventorySlots.entrySet());
    }

    @Override
    public boolean tick(Minecraft minecraft, AbstractContainerMenu menu) {
        if (finished) {
            return true;
        }
        int budget = MAX_CLICKS_PER_TICK;
        while (budget > 0 && !pending.isEmpty()) {
            Map.Entry<Integer, ItemStack> next = pending.pollFirst();
            int menuSlot = menuSlotFor(minecraft, menu, next.getKey());
            if (menuSlot < 0) {
                continue;
            }
            ItemStack now = menu.slots.get(menuSlot).getItem();
            if (now.isEmpty() || !ItemStack.isSameItemSameComponents(now, next.getValue())) {
                continue; // The slot changed since planning; leave it alone.
            }
            minecraft.gameMode.handleContainerInput(menu.containerId, menuSlot, 0, ContainerInput.QUICK_MOVE,
                    minecraft.player);
            budget--;
        }
        if (!pending.isEmpty()) {
            return false;
        }
        if (!menu.getCarried().isEmpty()) {
            cleanup(minecraft, menu);
        }
        if (settleTicks < 0) {
            settleTicks = TakeOperator.settleTicks(minecraft);
        }
        if (settleTicks-- > 0) {
            return false;
        }
        finished = true;
        return true;
    }

    /** Shift-clicks never use the cursor; this only guards against the unexpected. */
    @Override
    public void cleanup(Minecraft minecraft, AbstractContainerMenu menu) {
        pending.clear();
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            return;
        }
        Inventory inventory = minecraft.player.getInventory();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            ItemStack in = slot.getItem();
            if (slot.container == inventory && slot.getContainerSlot() < 36 && (in.isEmpty()
                    || (ItemStack.isSameItemSameComponents(in, carried)
                    && in.getCount() + carried.getCount() <= in.getMaxStackSize()))) {
                minecraft.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.PICKUP, minecraft.player);
                StorageTerminal.LOGGER.warn("Put {} back from the cursor into slot {}", carried, i);
                return;
            }
        }
        StorageTerminal.LOGGER.error("Could not put back {} from the cursor", carried);
    }

    /** The menu slot that shows player inventory slot {@code index}, or -1. */
    static int menuSlotFor(Minecraft minecraft, AbstractContainerMenu menu, int index) {
        Inventory inventory = minecraft.player.getInventory();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == inventory && slot.getContainerSlot() == index) {
                return i;
            }
        }
        return -1;
    }
}
