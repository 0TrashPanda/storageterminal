// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import com.mojang.blaze3d.platform.InputConstants;
import dev.trashpanda.storageterminal.core.ContainerKey;
import dev.trashpanda.storageterminal.core.ContainerRecord;
import dev.trashpanda.storageterminal.core.ContainerState;
import dev.trashpanda.storageterminal.core.CountFormat;
import dev.trashpanda.storageterminal.core.ItemKey;
import dev.trashpanda.storageterminal.core.SortOrder;
import dev.trashpanda.storageterminal.core.StorageCache;
import dev.trashpanda.storageterminal.core.StorageIndex;
import dev.trashpanda.storageterminal.core.TakePlanner;
import dev.trashpanda.storageterminal.core.TerminalList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * The storage terminal (SPEC section 8, layout A from the approved mockups): search, sort and
 * rescan on top, then a status line, the aggregated item grid with a scrollbar, and the player's
 * inventory. Clicking a grid entry takes items (M3); clicking an inventory stack stores it (M4).
 * Alt + click moves all of an item, Shift + wheel moves one, Shift-drag moves a stack of
 * everything the mouse passes over, and plain clicks work with a held stack like a vanilla
 * cursor (SPEC 12b; the held stack really stays in an inventory slot, see StorageService).
 *
 * <p>Controls are placed by {@link #layout()} only, so they can move to a sidebar (layout B)
 * later without touching the rest.
 */
public final class TerminalScreen extends Screen {
    private static final int COLUMNS = 9;
    private static final int MAX_ROWS = 6;
    private static final int SLOT = 18;
    private static final int PAD = 7;
    private static final int SCROLLBAR_W = 12;
    private static final int PANEL_W = PAD + COLUMNS * SLOT + 4 + SCROLLBAR_W + PAD;
    /** Panel height without the grid rows. */
    private static final int FIXED_H = 45 + 5 + 12 + 3 * SLOT + 4 + SLOT + PAD;

    private static final int TEXT = 0xFF404040;
    private static final int PANEL = 0xFFC6C6C6;
    private static final int AMBER = 0xFFD98A00;
    /** Width available to the status line. */
    private static final int STATUS_W = PANEL_W - 2 * PAD;

    private final StorageService service = StorageService.get();
    private final Map<ItemKey, ItemStack> stackCache = new HashMap<>();
    private final Map<ItemKey, List<String>> tagCache = new HashMap<>();
    private final TerminalList.ItemInfo itemInfo = new TerminalList.ItemInfo() {
        @Override
        public String displayName(ItemKey item) {
            ItemStack stack = stackFor(item);
            return stack.isEmpty() ? item.itemId() : stack.getHoverName().getString();
        }

        @Override
        public Collection<String> tags(ItemKey item) {
            return tagCache.computeIfAbsent(item, k -> stackFor(k).typeHolder().tags()
                    .map(tag -> tag.location().toString()).toList());
        }
    };

    private EditBox search;
    private Button sortButton;
    private Button rescanButton;
    private Button depositAllButton;
    private Button cancelButton;

    private int left;
    private int top;
    private int rows;
    private int gridX;
    private int gridY;
    private int invY;

    private StorageCache seenCache;
    private int seenVersion = -1;
    private String seenQuery;
    private SortOrder seenSort;
    private int seenReachableHash;
    private int seenPredictionId = -1;
    private boolean seenFrozen;
    /** Totals when the current prediction started (see StorageService#predicted). */
    private final Map<ItemKey, Long> predictionBaseline = new HashMap<>();
    private List<StorageIndex.Entry> realEntries = List.of();
    private List<StorageIndex.Entry> allEntries = List.of();
    private List<StorageIndex.Entry> shown = List.of();
    private int scrollRow;
    private boolean draggingScrollbar;
    /** Shift-drag: what this drag already passed over, so each stack or entry is moved once. */
    private boolean shiftDragging;
    private final java.util.Set<Integer> dragInventory = new java.util.HashSet<>();
    private final java.util.Set<ItemKey> dragEntries = new java.util.HashSet<>();
    /** Full status breakdown, shown on hover when the status line had to be shortened. */
    private List<Component> statusDetail;

    TerminalScreen() {
        super(Component.literal("Storage Terminal"));
    }

    @Override
    protected void init() {
        String query = search == null ? "" : search.getValue();
        search = new EditBox(font, 0, 0, 98, 14, Component.literal("Search"));
        search.setMaxLength(64);
        search.setHint(Component.literal("Search  @mod #tag").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(query);
        search.setResponder(value -> scrollRow = 0);
        sortButton = Button.builder(Component.literal(service.sortOrder().label()), b -> {
            service.setSortOrder(service.sortOrder().next());
            b.setMessage(Component.literal(service.sortOrder().label()));
            scrollRow = 0;
        }).bounds(0, 0, 46, 14).build();
        rescanButton = Button.builder(Component.literal("Scan"),
                b -> service.startTerminalScan(minecraft, true)).bounds(0, 0, 30, 14).build();
        depositAllButton = Button.builder(Component.literal("Deposit all"),
                b -> service.startPut(minecraft, service.depositAllSlots())).bounds(0, 0, 62, 14).build();
        cancelButton = Button.builder(Component.literal("Cancel"), b -> service.cancelJobs("cancelled"))
                .bounds(0, 0, 40, 12).build();
        cancelButton.visible = false;
        layout();
        addRenderableWidget(search);
        addRenderableWidget(sortButton);
        addRenderableWidget(rescanButton);
        addRenderableWidget(depositAllButton);
        addRenderableWidget(cancelButton);
        setInitialFocus(search);
    }

    /** All positions in one place. Grid rows shrink on small windows (at least one row). */
    private void layout() {
        rows = Math.max(1, Math.min(MAX_ROWS, (height - FIXED_H - 8) / SLOT));
        int panelH = FIXED_H + rows * SLOT;
        left = (width - PANEL_W) / 2;
        top = Math.max(0, (height - panelH) / 2);
        search.setPosition(left + PAD, top + 17);
        sortButton.setPosition(left + PAD + 100, top + 17);
        rescanButton.setPosition(left + PANEL_W - PAD - 30, top + 17);
        gridX = left + PAD;
        gridY = top + 45;
        invY = gridY + rows * SLOT + 5 + 12;
        depositAllButton.setPosition(left + PAD + COLUMNS * SLOT - 62, invY - 16);
        cancelButton.setPosition(left + PANEL_W - PAD - 40, top + 31);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }

    @Override
    public void removed() {
        service.onTerminalClosed();
        super.removed();
    }

    @Override
    public void tick() {
        if (minecraft.player == null || minecraft.player.isDeadOrDying()) {
            onClose();
        }
    }

    // ---- data -----------------------------------------------------------------------------

    private void refreshEntries() {
        StorageCache cache = service.cache();
        String query = search.getValue();
        int reachableHash = service.reachable().hashCode();
        if (cache == seenCache && cache.version() == seenVersion && reachableHash == seenReachableHash
                && query.equals(seenQuery) && service.sortOrder() == seenSort && service.predictionId() == seenPredictionId
                && frozen() == seenFrozen) {
            return;
        }
        // While something moves, keep every entry where it is (the player chose search and sort, so those still apply).
        boolean keepPlaces = frozen() && query.equals(seenQuery) && service.sortOrder() == seenSort;
        seenFrozen = frozen();
        boolean dataChanged = cache != seenCache || cache.version() != seenVersion || reachableHash != seenReachableHash;
        seenCache = cache;
        seenVersion = cache.version();
        seenReachableHash = reachableHash;
        seenQuery = query;
        seenSort = service.sortOrder();
        if (dataChanged) {
            List<ContainerRecord> inReach = new ArrayList<>();
            for (ContainerRecord record : cache.records()) {
                if (service.reachable().contains(record.key())) {
                    inReach.add(record);
                }
            }
            realEntries = StorageIndex.aggregate(inReach);
        }
        if (service.predictionId() != seenPredictionId) {
            // A move started or ended: remember the totals it starts from.
            seenPredictionId = service.predictionId();
            predictionBaseline.clear();
            for (ItemKey item : service.predicted().keySet()) {
                long total = 0;
                for (StorageIndex.Entry entry : realEntries) {
                    if (entry.item().equals(item)) {
                        total = entry.total();
                    }
                }
                predictionBaseline.put(item, total);
            }
        }
        allEntries = StorageIndex.withPredicted(realEntries, predictionBaseline, service.predicted(), System.currentTimeMillis());
        List<StorageIndex.Entry> sorted = TerminalList.filterAndSort(allEntries, query, seenSort, itemInfo);
        shown = keepPlaces ? TerminalList.keepOrder(sorted, shown) : sorted;
        scrollRow = Math.min(scrollRow, maxScrollRow());
    }

    /** Grid order is frozen while items move or a Shift-drag is under way. */
    private boolean frozen() {
        return shiftDragging || service.movesPending();
    }

    private ItemStack stackFor(ItemKey key) {
        return stackCache.computeIfAbsent(key, k -> minecraft.level == null ? ItemStack.EMPTY
                : ItemKeys.toStack(k, 1, minecraft.level.registryAccess()));
    }

    private int maxScrollRow() {
        int totalRows = (shown.size() + COLUMNS - 1) / COLUMNS;
        return Math.max(0, totalRows - rows);
    }

    // ---- drawing --------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        refreshEntries();
        drawPanel(g, left, top, PANEL_W, FIXED_H + rows * SLOT);
        g.text(font, title, left + PAD, top + 6, TEXT, false);
        g.text(font, "?", helpX(), top + 6, TEXT, false);
        cancelButton.visible = service.runningTake() != null || service.runningPut() != null;
        drawStatus(g);
        drawGrid(g);
        drawScrollbar(g);
        g.text(font, "Inventory", left + PAD, invY - 12, TEXT, false);
        drawInventory(g);
        super.extractRenderState(g, mouseX, mouseY, a);
        drawEmptyState(g);

        g.nextStratum();
        StorageIndex.Entry hovered = entryAt(mouseX, mouseY);
        int hoveredSlot = gridSlotAt(mouseX, mouseY);
        if (hoveredSlot >= 0) {
            int x = gridX + (hoveredSlot % COLUMNS) * SLOT;
            int y = gridY + (hoveredSlot / COLUMNS) * SLOT;
            g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0x80FFFFFF);
        }
        drawStaleMarkers(g);
        drawBusy(g);
        ItemStack held = service.heldStack(minecraft);
        if (!held.isEmpty()) {
            // Held items follow the mouse; no tooltips meanwhile, as in vanilla.
            g.nextStratum();
            g.item(held, mouseX - 8, mouseY - 8);
            g.itemDecorations(font, held, mouseX - 8, mouseY - 8);
        } else if (hovered != null) {
            ItemStack stack = stackFor(hovered.item());
            g.setTooltipForNextFrame(font, tooltipFor(hovered, stack), stack.getTooltipImage(), mouseX, mouseY);
        } else {
            ItemStack invStack = inventoryStackAt(mouseX, mouseY);
            if (!invStack.isEmpty()) {
                g.setTooltipForNextFrame(font, invStack, mouseX, mouseY);
            } else if (onHelp(mouseX, mouseY)) {
                g.setTooltipForNextFrame(font, HELP, java.util.Optional.empty(), mouseX, mouseY);
            } else if (statusDetail != null && mouseX >= left + PAD && mouseX < left + PAD + STATUS_W
                    && mouseY >= top + 32 && mouseY < top + 43) {
                g.setTooltipForNextFrame(font, statusDetail, java.util.Optional.empty(), mouseX, mouseY);
            }
        }
    }

    private void drawStatus(GuiGraphicsExtractor g) {
        int x = left + PAD;
        int y = top + 34;
        statusDetail = null;
        TakeJob take = service.runningTake();
        if (take != null) {
            String where = take.stopCount() > 1 ? " (" + Math.max(1, take.currentStop()) + "/" + take.stopCount() + ")" : "";
            String text = "Taking " + CountFormat.exact(take.wanted()) + " " + take.itemName() + where;
            g.text(font, clip(text, STATUS_W - 44), x, y, TEXT, false);
            if (font.width(text) > STATUS_W - 44) {
                statusDetail = List.of(Component.literal(text));
            }
            return;
        }
        PutJob put = service.runningPut();
        if (put != null) {
            String where = put.visitCount() > 1 ? " (" + Math.max(1, put.currentVisit()) + "/" + put.visitCount() + ")" : "";
            String text = "Storing " + put.what() + where;
            g.text(font, clip(text, STATUS_W - 44), x, y, TEXT, false);
            if (font.width(text) > STATUS_W - 44) {
                statusDetail = List.of(Component.literal(text));
            }
            return;
        }
        StorageService.Feedback feedback = service.feedback();
        if (feedback != null) {
            drawFeedbackIcon(g, x, y, feedback.kind());
            String text = feedback.text();
            g.text(font, clip(text, STATUS_W - 12), x + 12, y, TEXT, false);
            if (font.width(text) > STATUS_W - 12) {
                statusDetail = List.of(Component.literal(text));
            }
            return;
        }
        ScanJob scan = service.runningScan();
        if (scan != null) {
            if (scan.isWaitingForSneak()) {
                g.text(font, "Stop sneaking to scan containers", x, y, TEXT, false);
                return;
            }
            String text = "Scanning " + scan.done() + " / " + scan.total();
            g.text(font, text, x, y, TEXT, false);
            int barX = x + font.width(text) + 6;
            int barW = 60;
            g.fill(barX, y + 1, barX + barW, y + 7, 0xFF373737);
            g.fill(barX + 1, y + 2, barX + barW - 1, y + 6, 0xFF555555);
            int filled = scan.total() == 0 ? 0 : (barW - 2) * scan.done() / scan.total();
            g.fill(barX + 1, y + 2, barX + 1 + filled, y + 6, 0xFF5AC35A);
            return;
        }
        ScanJob.Summary last = service.lastTerminalScan();
        if (last != null && last.abortReason() != null && !"terminal closed".equals(last.abortReason())
                && !"take".equals(last.abortReason())) {
            g.text(font, clip("Scan stopped: " + last.abortReason(), STATUS_W), x, y, TEXT, false);
            return;
        }
        int inReach = 0;
        int stale = 0;
        int unreachable = 0;
        int ignored = 0;
        for (ContainerKey key : service.reachable()) {
            ContainerRecord record = service.cache().get(key);
            inReach++;
            if (record == null) {
                continue;
            }
            if (record.isIgnored()) {
                ignored++;
            } else if (record.state() == ContainerState.UNREACHABLE) {
                unreachable++;
            } else if (record.isStale()) {
                stale++;
            }
        }
        if (inReach == 0) {
            return;
        }
        if (stale > 0) {
            g.fill(x, y + 1, x + 5, y + 6, 0xFF3F2600);
            g.fill(x + 1, y + 2, x + 4, y + 5, AMBER);
            g.text(font, stale + " not rescanned yet", x + 8, y, TEXT, false);
            return;
        }
        String containers = inReach + (inReach == 1 ? " container" : " containers");
        StringBuilder full = new StringBuilder(containers);
        if (unreachable > 0) {
            full.append(" · ").append(unreachable).append(" unreachable");
        }
        if (ignored > 0) {
            full.append(" · ").append(ignored).append(" ignored");
        }
        String text = full.toString();
        if (font.width(text) > STATUS_W) {
            // Too long for the panel: short form, full breakdown on hover.
            text = containers + " · " + (unreachable + ignored) + " skipped";
            statusDetail = List.of(
                    Component.literal(containers + " in reach"),
                    Component.literal(unreachable + " unreachable (blocked or did not open)").withStyle(ChatFormatting.GRAY),
                    Component.literal(ignored + " ignored").withStyle(ChatFormatting.GRAY));
        }
        g.text(font, text, x, y, TEXT, false);
    }

    private void drawGrid(GuiGraphicsExtractor g) {
        int first = scrollRow * COLUMNS;
        for (int i = 0; i < rows * COLUMNS; i++) {
            int x = gridX + (i % COLUMNS) * SLOT;
            int y = gridY + (i / COLUMNS) * SLOT;
            drawSlot(g, x, y);
            int index = first + i;
            if (index < shown.size()) {
                StorageIndex.Entry entry = shown.get(index);
                ItemStack stack = stackFor(entry.item());
                if (!stack.isEmpty()) {
                    g.item(stack, x + 1, y + 1);
                    // 0: all of it is being taken; it stays (dimmed) until the move is done.
                    g.itemDecorations(font, stack, x + 1, y + 1,
                            entry.total() <= 1 ? "" : CountFormat.compact(entry.total()));
                }
            }
        }
    }

    /** Entries of a running or queued take are dimmed until it finishes. */
    private void drawBusy(GuiGraphicsExtractor g) {
        TakeJob take = service.runningTake();
        java.util.Set<ItemKey> busy = new java.util.HashSet<>(service.queuedTakeItems());
        if (take != null) {
            busy.add(take.item());
        }
        if (busy.isEmpty()) {
            return;
        }
        int first = scrollRow * COLUMNS;
        for (int i = 0; i < rows * COLUMNS && first + i < shown.size(); i++) {
            if (busy.contains(shown.get(first + i).item())) {
                int x = gridX + (i % COLUMNS) * SLOT;
                int y = gridY + (i / COLUMNS) * SLOT;
                g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0xA08B8B8B);
            }
        }
    }

    /** Green check, amber "!", red cross: told apart by shape as well as colour. */
    private void drawFeedbackIcon(GuiGraphicsExtractor g, int x, int y, StorageService.FeedbackKind kind) {
        int colour = switch (kind) {
            case SUCCESS -> 0xFF2F7D32;
            case PARTIAL -> 0xFFB36B00;
            case FAILED -> 0xFFB3261E;
        };
        g.fill(x, y - 1, x + 9, y + 8, colour);
        int w = 0xFFFFFFFF;
        switch (kind) {
            case SUCCESS -> {
                g.fill(x + 2, y + 3, x + 3, y + 5, w);
                g.fill(x + 3, y + 4, x + 4, y + 6, w);
                g.fill(x + 4, y + 3, x + 5, y + 5, w);
                g.fill(x + 5, y + 2, x + 6, y + 4, w);
                g.fill(x + 6, y + 1, x + 7, y + 3, w);
            }
            case PARTIAL -> {
                g.fill(x + 4, y + 1, x + 5, y + 4, w);
                g.fill(x + 4, y + 5, x + 5, y + 6, w);
            }
            case FAILED -> {
                for (int i = 0; i < 5; i++) {
                    g.fill(x + 2 + i, y + 1 + i, x + 3 + i, y + 2 + i, w);
                    g.fill(x + 6 - i, y + 1 + i, x + 7 - i, y + 2 + i, w);
                }
            }
        }
    }

    private void drawStaleMarkers(GuiGraphicsExtractor g) {
        int first = scrollRow * COLUMNS;
        for (int i = 0; i < rows * COLUMNS && first + i < shown.size(); i++) {
            if (shown.get(first + i).anyStale()) {
                int x = gridX + (i % COLUMNS) * SLOT + 1;
                int y = gridY + (i / COLUMNS) * SLOT + 1;
                g.fill(x, y, x + 5, y + 5, 0xFF3F2600);
                g.fill(x + 1, y + 1, x + 4, y + 4, AMBER);
            }
        }
    }

    private void drawScrollbar(GuiGraphicsExtractor g) {
        int x = gridX + COLUMNS * SLOT + 4;
        int y = gridY;
        int h = rows * SLOT;
        drawWell(g, x, y, SCROLLBAR_W, h);
        int max = maxScrollRow();
        int thumbH = max == 0 ? h - 2 : Math.max(10, (h - 2) * rows / (rows + max));
        int thumbY = max == 0 ? y + 1 : y + 1 + (h - 2 - thumbH) * scrollRow / max;
        g.fill(x + 1, thumbY, x + SCROLLBAR_W - 1, thumbY + thumbH, max == 0 ? 0xFF9A9A9A : PANEL);
        g.fill(x + 1, thumbY, x + SCROLLBAR_W - 2, thumbY + 1, 0xFFFFFFFF);
        g.fill(x + 1, thumbY, x + 2, thumbY + thumbH - 1, 0xFFFFFFFF);
        g.fill(x + 2, thumbY + thumbH - 1, x + SCROLLBAR_W - 1, thumbY + thumbH, 0xFF555555);
        g.fill(x + SCROLLBAR_W - 2, thumbY + 1, x + SCROLLBAR_W - 1, thumbY + thumbH, 0xFF555555);
    }

    private void drawInventory(GuiGraphicsExtractor g) {
        Inventory inventory = minecraft.player == null ? null : minecraft.player.getInventory();
        PutJob put = service.runningPut();
        int held = service.cursorSlot();
        java.util.Set<Integer> storing = new java.util.HashSet<>(service.queuedStoreSlots());
        if (put != null) {
            storing.addAll(put.pendingInventorySlots());
        }
        for (int i = 0; i < 36; i++) {
            int[] xy = inventorySlotXY(i);
            drawSlot(g, xy[0], xy[1]);
            if (inventory != null) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && i != held) { // The held stack is drawn at the mouse instead.
                    g.item(stack, xy[0] + 1, xy[1] + 1);
                    g.itemDecorations(font, stack, xy[0] + 1, xy[1] + 1);
                    if (storing.contains(i)) {
                        g.nextStratum();
                        g.fill(xy[0] + 1, xy[1] + 1, xy[0] + SLOT - 1, xy[1] + SLOT - 1, 0xA08B8B8B);
                    }
                }
            }
        }
    }

    private void drawEmptyState(GuiGraphicsExtractor g) {
        if (!shown.isEmpty()) {
            return;
        }
        int cx = gridX + COLUMNS * SLOT / 2;
        int cy = gridY + rows * SLOT / 2 - 9;
        if (service.reachable().isEmpty()) {
            g.centeredText(font, "No containers in reach", cx, cy, 0xFFFFFFFF);
            g.centeredText(font, "Stand near a chest or barrel", cx, cy + 12, 0xFFE0E0E0);
        } else if (!search.getValue().isBlank()) {
            g.centeredText(font, "Nothing matches", cx, cy, 0xFFFFFFFF);
            g.centeredText(font, "“" + clip(search.getValue(), 140) + "”", cx, cy + 12, 0xFFE0E0E0);
        } else if (service.runningScan() != null) {
            g.centeredText(font, "Looking into containers...", cx, cy + 6, 0xFFFFFFFF);
        } else {
            g.centeredText(font, "The containers in reach are empty", cx, cy + 6, 0xFFFFFFFF);
        }
    }

    private String clip(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String cut = text;
        while (!cut.isEmpty() && font.width(cut + "...") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    private static void drawPanel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL);
        g.fill(x + 1, y + 1, x + w - 2, y + 2, 0xFFFFFFFF);
        g.fill(x + 1, y + 1, x + 2, y + h - 2, 0xFFFFFFFF);
        g.fill(x + 2, y + h - 2, x + w - 1, y + h - 1, 0xFF555555);
        g.fill(x + w - 2, y + 2, x + w - 1, y + h - 1, 0xFF555555);
    }

    private static void drawSlot(GuiGraphicsExtractor g, int x, int y) {
        drawWell(g, x, y, SLOT, SLOT);
    }

    private static void drawWell(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF8B8B8B);
        g.fill(x, y, x + w - 1, y + 1, 0xFF373737);
        g.fill(x, y, x + 1, y + h - 1, 0xFF373737);
        g.fill(x + 1, y + h - 1, x + w, y + h, 0xFFFFFFFF);
        g.fill(x + w - 1, y + 1, x + w, y + h, 0xFFFFFFFF);
    }

    // ---- tooltip --------------------------------------------------------------------------

    /** Item tooltip plus the exact total and click hints. Which containers hold it is not shown. */
    private List<Component> tooltipFor(StorageIndex.Entry entry, ItemStack stack) {
        List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(minecraft, stack));
        lines.add(Component.literal(CountFormat.exact(entry.total()) + " stored").withStyle(ChatFormatting.GRAY));
        if (entry.anyStale()) {
            lines.add(Component.literal("Not rescanned yet").withStyle(ChatFormatting.GOLD));
        }
        return lines;
    }

    /** The controls, shown when hovering the "?" in the title bar (kept out of item tooltips so they stay small). */
    private static final List<Component> HELP = List.of(
            Component.literal("Controls"),
            Component.literal("Click: a stack onto the cursor \u00B7 Right-click: half").withStyle(ChatFormatting.GRAY),
            Component.literal("Holding: click the grid to store (right: one)").withStyle(ChatFormatting.GRAY),
            Component.literal("Holding: click outside to drop").withStyle(ChatFormatting.GRAY),
            Component.literal("Shift-click: a stack to or from the inventory").withStyle(ChatFormatting.GRAY),
            Component.literal("Alt-click: all of an item").withStyle(ChatFormatting.GRAY),
            Component.literal("Shift + wheel: one item \u00B7 Shift-drag: many").withStyle(ChatFormatting.GRAY));

    private int helpX() {
        return left + PANEL_W - PAD - 6;
    }

    private boolean onHelp(int x, int y) {
        return x >= helpX() - 2 && x < helpX() + 8 && y >= top + 4 && y < top + 15;
    }

    // ---- input ----------------------------------------------------------------------------

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!search.isFocused() && minecraft.options.keyInventory.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        // Some systems turn Shift + wheel into a sideways scroll.
        double notch = scrollY != 0 ? scrollY : scrollX;
        if (notch != 0 && minecraft.hasShiftDown() && wheelMove((int) x, (int) y, notch > 0)) {
            return true;
        }
        if (scrollY != 0) {
            scrollRow = Math.max(0, Math.min(maxScrollRow(), scrollRow - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    /**
     * Shift + wheel over a grid entry or an inventory stack: wheel up stores one, wheel down takes
     * one (onto the stack under the mouse, when over the inventory). False if not over either.
     */
    private boolean wheelMove(int x, int y, boolean up) {
        StorageIndex.Entry entry = entryAt(x, y);
        if (entry != null) {
            service.wheel(minecraft, entry.item(), !up, -1);
            return true;
        }
        int inventoryIndex = inventoryIndexAt(x, y);
        if (inventoryIndex >= 0 && minecraft.player != null && minecraft.level != null) {
            ItemStack stack = minecraft.player.getInventory().getItem(inventoryIndex);
            if (!stack.isEmpty()) {
                service.wheel(minecraft, ItemKeys.toKey(stack, minecraft.level.registryAccess()), !up, inventoryIndex);
            }
            return true;
        }
        return gridSlotAt(x, y) >= 0;
    }

    private boolean altDown(MouseButtonEvent event) {
        return event.hasAltDown() || minecraft.hasAltDown();
    }

    private boolean shiftDown(MouseButtonEvent event) {
        return event.hasShiftDown() || minecraft.hasShiftDown();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        dragInventory.clear();
        dragEntries.clear();
        shiftDragging = false;
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && onScrollbar(event.x(), event.y())) {
            draggingScrollbar = true;
            scrollTo(event.y());
            return true;
        }
        int inventoryIndex = inventoryIndexAt((int) event.x(), (int) event.y());
        if (inventoryIndex >= 0 && minecraft.player != null) {
            boolean left = event.button() == InputConstants.MOUSE_BUTTON_LEFT;
            boolean right = event.button() == InputConstants.MOUSE_BUTTON_RIGHT;
            ItemStack clicked = minecraft.player.getInventory().getItem(inventoryIndex);
            if (left && altDown(event)) {
                // Alt-click: every stack of this item, main inventory and hotbar (never the held one).
                if (!clicked.isEmpty() && inventoryIndex != service.heldSlot()) {
                    List<Integer> slots = new ArrayList<>();
                    for (int i = 0; i < 36; i++) {
                        ItemStack other = minecraft.player.getInventory().getItem(i);
                        if (!other.isEmpty() && ItemStack.isSameItemSameComponents(other, clicked)) {
                            slots.add(i);
                        }
                    }
                    service.startPut(minecraft, slots);
                }
            } else if (left && shiftDown(event)) {
                // Shift-click: this stack, queued if a job runs; the drag may add more.
                shiftDragging = true;
                dragInventory.add(inventoryIndex);
                service.queueStore(minecraft, inventoryIndex);
            } else if (left || right) {
                // Plain click: pick up, place, swap or merge, like a vanilla inventory.
                service.cursorClickInventory(minecraft, inventoryIndex, right);
            }
            return true;
        }
        if (gridSlotAt((int) event.x(), (int) event.y()) >= 0) {
            StorageIndex.Entry entry = entryAt((int) event.x(), (int) event.y());
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && shiftDown(event) && !altDown(event)) {
                // Shift-click: one stack, queued if a job runs; the drag may add more.
                shiftDragging = true;
                if (entry != null) {
                    dragEntries.add(entry.item());
                    service.queueTake(minecraft, entry.item());
                }
                return true;
            }
            boolean left = event.button() == InputConstants.MOUSE_BUTTON_LEFT;
            boolean right = event.button() == InputConstants.MOUSE_BUTTON_RIGHT;
            if (left && altDown(event)) {
                if (entry != null) {
                    service.startTake(minecraft, entry.item(), TakePlanner.Mode.FILL); // As much as fits.
                }
            } else if ((left || right) && service.heldSlot() >= 0) {
                service.storeHeld(minecraft, right); // Holding something: store it (right: one item).
            } else if ((left || right) && entry != null) {
                service.takeToCursor(minecraft, entry.item(), right ? TakePlanner.Mode.HALF : TakePlanner.Mode.STACK);
            }
            return true;
        }
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (!insidePanel(event.x(), event.y()) && service.heldSlot() >= 0) {
            // Outside the panel: drop the held stack, like vanilla (right: one item).
            service.dropHeld(minecraft, event.button() == InputConstants.MOUSE_BUTTON_RIGHT);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingScrollbar) {
            scrollTo(event.y());
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && minecraft.player != null
                && (shiftDragging || shiftDown(event))) {
            shiftDragOver((int) event.x(), (int) event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingScrollbar = false;
        shiftDragging = false;
        return super.mouseReleased(event);
    }

    /** Shift-drag passed over (x, y): store that inventory stack or take a stack of that entry, once per drag. */
    private void shiftDragOver(int x, int y) {
        shiftDragging = true;
        int inventoryIndex = inventoryIndexAt(x, y);
        if (inventoryIndex >= 0) {
            if (!minecraft.player.getInventory().getItem(inventoryIndex).isEmpty() && dragInventory.add(inventoryIndex)) {
                service.queueStore(minecraft, inventoryIndex);
            }
            return;
        }
        StorageIndex.Entry entry = entryAt(x, y);
        if (entry != null && dragEntries.add(entry.item())) {
            service.queueTake(minecraft, entry.item());
        }
    }

    private boolean insidePanel(double x, double y) {
        return x >= left && x < left + PANEL_W && y >= top && y < top + FIXED_H + rows * SLOT;
    }

    private boolean onScrollbar(double x, double y) {
        int sx = gridX + COLUMNS * SLOT + 4;
        return x >= sx && x < sx + SCROLLBAR_W && y >= gridY && y < gridY + rows * SLOT;
    }

    private void scrollTo(double mouseY) {
        int max = maxScrollRow();
        double f = (mouseY - gridY) / (rows * SLOT);
        scrollRow = Math.max(0, Math.min(max, (int) Math.round(f * max)));
    }

    private int gridSlotAt(int x, int y) {
        if (x < gridX || y < gridY || x >= gridX + COLUMNS * SLOT || y >= gridY + rows * SLOT) {
            return -1;
        }
        return ((y - gridY) / SLOT) * COLUMNS + (x - gridX) / SLOT;
    }

    private StorageIndex.Entry entryAt(int x, int y) {
        int slot = gridSlotAt(x, y);
        if (slot < 0) {
            return null;
        }
        int index = scrollRow * COLUMNS + slot;
        return index < shown.size() ? shown.get(index) : null;
    }

    /** Inventory slot i: 0-8 hotbar (bottom row), 9-35 main inventory (three rows above). */
    private int[] inventorySlotXY(int i) {
        if (i < 9) {
            return new int[] {gridX + i * SLOT, invY + 3 * SLOT + 4};
        }
        int j = i - 9;
        return new int[] {gridX + (j % 9) * SLOT, invY + (j / 9) * SLOT};
    }

    private ItemStack inventoryStackAt(int x, int y) {
        int index = inventoryIndexAt(x, y);
        return index < 0 || minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getInventory().getItem(index);
    }

    /** Inventory index (0-35) under the mouse, or -1. */
    private int inventoryIndexAt(int x, int y) {
        for (int i = 0; i < 36; i++) {
            int[] xy = inventorySlotXY(i);
            if (x >= xy[0] && x < xy[0] + SLOT && y >= xy[1] && y < xy[1] + SLOT) {
                return i;
            }
        }
        return -1;
    }

    /** Center of inventory slot {@code index} (0-8 hotbar, 9-35 main), in GUI coordinates. For tests. */
    int[] inventorySlotCenter(int index) {
        int[] xy = inventorySlotXY(index);
        return new int[] {xy[0] + SLOT / 2, xy[1] + SLOT / 2};
    }

    /** Center of the Deposit all button. For tests. */
    int[] depositAllCenter() {
        return new int[] {depositAllButton.getX() + depositAllButton.getWidth() / 2,
                depositAllButton.getY() + depositAllButton.getHeight() / 2};
    }

    // ---- for tests --------------------------------------------------------------------------

    /** The entries currently shown, after search and sort. */
    List<StorageIndex.Entry> shownEntries() {
        refreshEntries();
        return shown;
    }

    void setSearch(String query) {
        search.setValue(query);
    }

    String searchText() {
        return search.getValue();
    }

    /** Center of grid slot {@code index} (0 = top left), in GUI coordinates. */
    int[] gridSlotCenter(int index) {
        return new int[] {gridX + (index % COLUMNS) * SLOT + SLOT / 2, gridY + (index / COLUMNS) * SLOT + SLOT / 2};
    }
}
