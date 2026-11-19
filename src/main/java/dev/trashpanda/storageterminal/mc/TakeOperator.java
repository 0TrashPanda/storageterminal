// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import dev.trashpanda.storageterminal.StorageTerminal;
import dev.trashpanda.storageterminal.core.ItemKey;
import dev.trashpanda.storageterminal.core.SlotStack;
import dev.trashpanda.storageterminal.core.TakePlanner;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Takes up to {@code wanted} items of one kind out of one open container, using normal clicks:
 * shift-click for whole stacks, pick-up/place clicks for part of a stack. The slots are chosen
 * from the container's fresh contents (re-verify), partial stacks first. Clicks are spread over
 * ticks, and the result is read back after the server had time to correct anything.
 */
final class TakeOperator implements ContainerSession.Operator {
    /** At most this many clicks per tick, so it looks like a fast player, not a burst. */
    private static final int MAX_CLICKS_PER_TICK = 6;
    private static final int LEFT = 0;
    private static final int RIGHT = 1;

    private record Click(int slot, int button, ContainerInput input) {
    }

    private final ItemKey item;
    private final ItemStack reference;
    private final int maxStack;
    private final long wanted;
    private final int containerSlots;
    /** Inventory index (0-35) to put the items on if it fits, or -1 for anywhere. */
    private final int preferredInventoryIndex;

    private List<TakePlanner.SlotTake> takes;
    private int nextTake;
    private final Deque<Click> clicks = new ArrayDeque<>();
    private int currentSource = -1;
    private int settleTicks = -1;
    private boolean inventoryFull;
    private boolean finished;

    TakeOperator(ItemKey item, ItemStack reference, long wanted, int containerSlots, int preferredInventoryIndex) {
        this.item = item;
        this.preferredInventoryIndex = preferredInventoryIndex;
        this.reference = reference;
        this.maxStack = reference.getMaxStackSize();
        this.wanted = wanted;
        this.containerSlots = containerSlots;
    }

    boolean inventoryFull() {
        return inventoryFull;
    }

    @Override
    public boolean tick(Minecraft minecraft, AbstractContainerMenu menu) {
        if (finished) {
            return true;
        }
        if (takes == null) {
            takes = TakePlanner.slotsToTake(item, wanted, freshContainerSlots(minecraft, menu), maxStack);
        }
        int budget = MAX_CLICKS_PER_TICK;
        while (budget > 0) {
            if (clicks.isEmpty()) {
                if (inventoryFull || nextTake >= takes.size()) {
                    break;
                }
                planClicks(minecraft, menu, takes.get(nextTake++));
                continue;
            }
            Click click = clicks.pollFirst();
            minecraft.gameMode.handleContainerInput(menu.containerId, click.slot(), click.button(), click.input(),
                    minecraft.player);
            budget--;
            if (click.input() == ContainerInput.QUICK_MOVE && isOurs(menu.slots.get(click.slot()).getItem())) {
                // Shift-click left items behind: the inventory has no more room.
                inventoryFull = true;
            }
        }
        if (!clicks.isEmpty() || (!inventoryFull && nextTake < takes.size())) {
            return false;
        }
        if (!menu.getCarried().isEmpty()) {
            cleanup(minecraft, menu);
        }
        // Wait for the server to confirm or correct the clicks before reading the result.
        if (settleTicks < 0) {
            settleTicks = settleTicks(minecraft);
        }
        if (settleTicks-- > 0) {
            return false;
        }
        finished = true;
        return true;
    }

    /** Builds the clicks for one slot. Part of a stack goes through the cursor. */
    private void planClicks(Minecraft minecraft, AbstractContainerMenu menu, TakePlanner.SlotTake take) {
        ItemStack inSlot = menu.slots.get(take.slot()).getItem();
        if (!isOurs(inSlot)) {
            return; // Changed since we looked; the job re-plans with the next container.
        }
        int have = inSlot.getCount();
        int n = Math.min(take.count(), have);
        currentSource = take.slot();
        if (n == have && preferredInventoryIndex < 0) {
            clicks.add(new Click(take.slot(), LEFT, ContainerInput.QUICK_MOVE));
            return;
        }
        int target = inventoryTarget(minecraft, menu, n);
        if (target < 0) {
            inventoryFull = true;
            return;
        }
        if (n == (have + 1) / 2) {
            // Right-click picks up exactly half (rounded up); place it in one click.
            clicks.add(new Click(take.slot(), RIGHT, ContainerInput.PICKUP));
            clicks.add(new Click(target, LEFT, ContainerInput.PICKUP));
        } else if (n <= have - n) {
            // Pick up all, drop n one by one into the target, put the rest back.
            clicks.add(new Click(take.slot(), LEFT, ContainerInput.PICKUP));
            for (int i = 0; i < n; i++) {
                clicks.add(new Click(target, RIGHT, ContainerInput.PICKUP));
            }
            clicks.add(new Click(take.slot(), LEFT, ContainerInput.PICKUP));
        } else {
            // Pick up all, return (have - n) one by one, place the n left on the cursor.
            clicks.add(new Click(take.slot(), LEFT, ContainerInput.PICKUP));
            for (int i = 0; i < have - n; i++) {
                clicks.add(new Click(take.slot(), RIGHT, ContainerInput.PICKUP));
            }
            clicks.add(new Click(target, LEFT, ContainerInput.PICKUP));
        }
    }

    /**
     * A player-inventory slot (menu index) that can take {@code n} of our item in one go: a stack of
     * the same item with room first, then an empty main-inventory slot, then an empty hotbar slot.
     * The preferred slot (the stack the player scrolled over) wins if the items fit there.
     */
    private int inventoryTarget(Minecraft minecraft, AbstractContainerMenu menu, int n) {
        Inventory inventory = minecraft.player.getInventory();
        if (preferredInventoryIndex >= 0) {
            int preferred = PutOperator.menuSlotFor(minecraft, menu, preferredInventoryIndex);
            if (preferred >= 0) {
                ItemStack there = menu.slots.get(preferred).getItem();
                if (there.isEmpty() || (isOurs(there) && there.getCount() + n <= maxStack)) {
                    return preferred;
                }
            }
        }
        int emptyMain = -1;
        int emptyHotbar = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container != inventory || slot.getContainerSlot() >= 36) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                if (slot.getContainerSlot() >= 9 && emptyMain < 0) {
                    emptyMain = i;
                } else if (slot.getContainerSlot() < 9 && emptyHotbar < 0) {
                    emptyHotbar = i;
                }
            } else if (isOurs(stack) && stack.getCount() + n <= maxStack) {
                return i;
            }
        }
        return emptyMain >= 0 ? emptyMain : emptyHotbar;
    }

    /** Puts whatever is on the cursor back: into the slot it came from if possible, else anywhere it fits whole. */
    @Override
    public void cleanup(Minecraft minecraft, AbstractContainerMenu menu) {
        clicks.clear();
        for (int attempt = 0; attempt < 4 && !menu.getCarried().isEmpty(); attempt++) {
            ItemStack carried = menu.getCarried();
            int slot = -1;
            if (currentSource >= 0 && fits(menu.slots.get(currentSource).getItem(), carried)) {
                slot = currentSource;
            } else {
                slot = inventoryTarget(minecraft, menu, carried.getCount());
                if (slot < 0) {
                    for (int i = 0; i < containerSlots && i < menu.slots.size(); i++) {
                        if (fits(menu.slots.get(i).getItem(), carried)) {
                            slot = i;
                            break;
                        }
                    }
                }
            }
            if (slot < 0) {
                StorageTerminal.LOGGER.error("Could not put back {} from the cursor", carried);
                return;
            }
            minecraft.gameMode.handleContainerInput(menu.containerId, slot, LEFT, ContainerInput.PICKUP, minecraft.player);
            StorageTerminal.LOGGER.warn("Put {} back from the cursor into slot {}", carried, slot);
        }
    }

    private boolean fits(ItemStack inSlot, ItemStack carried) {
        return inSlot.isEmpty()
                || (ItemStack.isSameItemSameComponents(inSlot, carried) && inSlot.getCount() + carried.getCount() <= maxStack);
    }

    private boolean isOurs(ItemStack stack) {
        return !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, reference);
    }

    private List<SlotStack> freshContainerSlots(Minecraft minecraft, AbstractContainerMenu menu) {
        List<SlotStack> slots = new ArrayList<>(containerSlots);
        for (int i = 0; i < containerSlots && i < menu.slots.size(); i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            // Only our item matters for planning; avoid encoding every other stack.
            slots.add(isOurs(stack) ? new SlotStack(item, stack.getCount()) : SlotStack.EMPTY);
        }
        return slots;
    }

    /** Two ticks plus the round trip to the server. */
    static int settleTicks(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        int latencyMs = 0;
        if (minecraft.getConnection() != null && player != null) {
            PlayerInfo info = minecraft.getConnection().getPlayerInfo(player.getUUID());
            if (info != null) {
                latencyMs = info.getLatency();
            }
        }
        return 2 + (latencyMs + 49) / 50;
    }

    /** Count of our item in the player's main inventory (36 slots). */
    static int countInInventory(LocalPlayer player, ItemStack reference) {
        int total = 0;
        Inventory inventory = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, reference)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
