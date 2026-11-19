// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Plans "take N of X" (SPEC section 9): how much to take for each click mode, how much fits in
 * the inventory, and which slots to take it from (nearest container first, partial stacks before
 * full ones, so containers empty out and free slots).
 */
public final class TakePlanner {
    /** What the click asked for. */
    public enum Mode {
        /** Left click or Shift + left click: one full stack, or all if there is less. */
        STACK,
        /** Right click: half a stack, rounded up. */
        HALF,
        /** Alt + left click: as much as fits in the inventory. */
        FILL,
        /** Shift + wheel: a single item. */
        ONE
    }

    /** Take {@code count} items from slot {@code slot} of a container. */
    public record SlotTake(int slot, int count) {
    }

    /** One step of a plan: take {@code count} from {@code slot} of {@code source}. */
    public record TakeOp(StorageSource source, int slot, int count) {
    }

    private TakePlanner() {
    }

    /** Room for {@code item} in the player's main inventory (36 slots; armor and offhand are not used). */
    public static int inventoryRoom(ItemKey item, int maxStack, List<SlotStack> inventory) {
        int room = 0;
        for (SlotStack slot : inventory) {
            if (slot.isEmpty()) {
                room += maxStack;
            } else if (slot.item().equals(item)) {
                room += Math.max(0, maxStack - slot.count());
            }
        }
        return room;
    }

    /** How many to take for a click, before the inventory limit. */
    public static long requested(Mode mode, long stored, int maxStack) {
        long oneStack = Math.min(maxStack, stored);
        return switch (mode) {
            case STACK -> oneStack;
            case HALF -> (oneStack + 1) / 2;
            case FILL -> stored;
            case ONE -> Math.min(1, stored);
        };
    }

    /** {@link #requested} limited to what fits. 0 means the inventory is full. */
    public static long amountToTake(Mode mode, long stored, int maxStack, int room) {
        return Math.min(requested(mode, stored, maxStack), room);
    }

    /**
     * Slots to take {@code wanted} items from, within one container: partial stacks first
     * (smallest first), then full ones. Takes less if the container holds less.
     */
    public static List<SlotTake> slotsToTake(ItemKey item, long wanted, List<SlotStack> slots, int maxStack) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            SlotStack s = slots.get(i);
            if (!s.isEmpty() && s.item().equals(item)) {
                indexes.add(i);
            }
        }
        indexes.sort(Comparator.comparingInt((Integer i) -> slots.get(i).count() >= maxStack ? 1 : 0)
                .thenComparingInt(i -> slots.get(i).count())
                .thenComparingInt(i -> i));
        List<SlotTake> takes = new ArrayList<>();
        long left = wanted;
        for (int index : indexes) {
            if (left <= 0) {
                break;
            }
            int n = (int) Math.min(left, slots.get(index).count());
            takes.add(new SlotTake(index, n));
            left -= n;
        }
        return takes;
    }

    /**
     * A full plan from cached contents. {@code nearestFirst} must already be sorted by distance;
     * unusable sources are skipped.
     */
    public static List<TakeOp> plan(ItemKey item, long wanted, List<? extends StorageSource> nearestFirst, int maxStack) {
        List<TakeOp> ops = new ArrayList<>();
        long left = wanted;
        for (StorageSource source : nearestFirst) {
            if (left <= 0) {
                break;
            }
            if (!source.isUsable()) {
                continue;
            }
            for (SlotTake take : slotsToTake(item, left, source.slots(), maxStack)) {
                ops.add(new TakeOp(source, take.slot(), take.count()));
                left -= take.count();
            }
        }
        return ops;
    }

    /** Total planned. */
    public static long total(List<TakeOp> ops) {
        long sum = 0;
        for (TakeOp op : ops) {
            sum += op.count();
        }
        return sum;
    }
}
