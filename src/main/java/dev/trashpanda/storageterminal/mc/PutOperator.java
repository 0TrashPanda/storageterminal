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
 *
 * <p>With a limit (Shift + wheel), only that many items are stored: the stack is picked up,
 * single items are right-clicked into the container (partial stacks of the item first, then
 * empty slots), and the rest is put back where it came from.
 */
final class PutOperator implements ContainerSession.Operator {
    private static final int MAX_CLICKS_PER_TICK = 6;
    private static final int LEFT = 0;
    private static final int RIGHT = 1;
    /** No limit: whole stacks by shift-click. */
    static final long WHOLE_STACKS = Long.MAX_VALUE;

    /** Inventory index (0-35) to move, and what it held when planned. */
    private final Deque<Map.Entry<Integer, ItemStack>> pending = new ArrayDeque<>();
    private int settleTicks = -1;
    private boolean finished;
    /** Items still to store one by one, or {@link #WHOLE_STACKS}. */
    private long left;
    /** Menu slot the stack on the cursor was picked up from, or -1. */
    private int carriedFrom = -1;

    PutOperator(Map<Integer, ItemStack> inventorySlots, long limit) {
        pending.addAll(inventorySlots.entrySet());
        this.left = limit;
    }

    @Override
    public boolean tick(Minecraft minecraft, AbstractContainerMenu menu) {
        if (finished) {
            return true;
        }
        int budget = MAX_CLICKS_PER_TICK;
        if (left != WHOLE_STACKS) {
            if (!tickLimited(minecraft, menu, budget)) {
                return false;
            }
            budget = 0;
        }
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

    /** One budget's worth of limited storing. True once nothing is left to click. */
    private boolean tickLimited(Minecraft minecraft, AbstractContainerMenu menu, int budget) {
        while (budget > 0) {
            if (carriedFrom >= 0) {
                ItemStack carried = menu.getCarried();
                if (carried.isEmpty()) {
                    carriedFrom = -1;
                    continue;
                }
                int target = left > 0 ? containerTarget(minecraft, menu, carried) : -1;
                if (target >= 0) {
                    click(minecraft, menu, target, RIGHT);
                    left--;
                } else {
                    click(minecraft, menu, carriedFrom, LEFT); // The rest goes back where it came from.
                    carriedFrom = -1;
                }
                budget--;
                continue;
            }
            if (left <= 0 || pending.isEmpty()) {
                pending.clear();
                return true;
            }
            Map.Entry<Integer, ItemStack> next = pending.pollFirst();
            int menuSlot = menuSlotFor(minecraft, menu, next.getKey());
            if (menuSlot < 0) {
                continue;
            }
            ItemStack now = menu.slots.get(menuSlot).getItem();
            if (now.isEmpty() || !ItemStack.isSameItemSameComponents(now, next.getValue())
                    || containerTarget(minecraft, menu, now) < 0) {
                continue;
            }
            click(minecraft, menu, menuSlot, LEFT);
            carriedFrom = menuSlot;
            budget--;
        }
        return false;
    }

    /** A container slot that takes one more of {@code stack}: a partial stack of it first, then an empty slot. */
    private static int containerTarget(Minecraft minecraft, AbstractContainerMenu menu, ItemStack stack) {
        Inventory inventory = minecraft.player.getInventory();
        int empty = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == inventory || !slot.mayPlace(stack)) {
                continue;
            }
            ItemStack in = slot.getItem();
            if (in.isEmpty()) {
                if (empty < 0) {
                    empty = i;
                }
            } else if (ItemStack.isSameItemSameComponents(in, stack) && in.getCount() < slot.getMaxStackSize(in)) {
                return i;
            }
        }
        return empty;
    }

    private static void click(Minecraft minecraft, AbstractContainerMenu menu, int slot, int button) {
        minecraft.gameMode.handleContainerInput(menu.containerId, slot, button, ContainerInput.PICKUP, minecraft.player);
    }

    /** Puts anything left on the cursor back into the inventory. */
    @Override
    public void cleanup(Minecraft minecraft, AbstractContainerMenu menu) {
        pending.clear();
        if (carriedFrom >= 0 && !menu.getCarried().isEmpty() && menu.slots.get(carriedFrom).getItem().isEmpty()) {
            click(minecraft, menu, carriedFrom, LEFT);
        }
        carriedFrom = -1;
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
