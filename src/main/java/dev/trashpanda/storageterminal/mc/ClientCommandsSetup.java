// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.trashpanda.storageterminal.StorageTerminal;
import dev.trashpanda.storageterminal.core.ContainerRecord;
import dev.trashpanda.storageterminal.core.ContainerState;
import dev.trashpanda.storageterminal.core.StorageIndex;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Client-side commands under {@code /storageterminal}. Nothing is sent to the server.
 * {@code version} and {@code cache clear} are always there; {@code scan} and {@code dump} are
 * DEBUG commands, only available with {@code debugLogging} on in the config.
 */
final class ClientCommandsSetup {
    private static final int DUMP_CHAT_LINES = 15;

    private ClientCommandsSetup() {
    }

    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal(StorageTerminal.MOD_ID)
                        .then(ClientCommands.literal("version").executes(ctx -> {
                            ctx.getSource().sendFeedback(Component.literal("Storage Terminal " + StorageTerminal.version()));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommands.literal("scan")
                                .requires(source -> StorageService.get().config().debugLogging())
                                .executes(ClientCommandsSetup::scan))
                        .then(ClientCommands.literal("dump")
                                .requires(source -> StorageService.get().config().debugLogging())
                                .executes(ClientCommandsSetup::dump))
                        .then(ClientCommands.literal("cache")
                                .then(ClientCommands.literal("clear").executes(ctx -> {
                                    StorageService.get().clearCache();
                                    ctx.getSource().sendFeedback(prefix().append("Cache cleared for this world."));
                                    return Command.SINGLE_SUCCESS;
                                })))));
    }

    private static int scan(CommandContext<FabricClientCommandSource> ctx) {
        Minecraft minecraft = ctx.getSource().getClient();
        String error = StorageService.get().startScan(minecraft, true, summary -> {
            if (minecraft.player == null) {
                return;
            }
            StringBuilder text = new StringBuilder();
            if (summary.abortReason() != null) {
                text.append("Scan stopped (").append(summary.abortReason()).append("). ");
            }
            text.append("Scanned ").append(summary.refreshed()).append(" containers in ")
                    .append(String.format("%.1f", summary.millis() / 1000.0)).append(" s");
            if (summary.unreachable() > 0) {
                text.append(", ").append(summary.unreachable()).append(" unreachable");
            }
            if (summary.ignored() > 0) {
                text.append(", ").append(summary.ignored()).append(" ignored");
            }
            minecraft.player.sendSystemMessage(prefix().append(text.append('.').toString()));
        });
        if (error != null) {
            ctx.getSource().sendError(prefix().append(error));
            return 0;
        }
        ctx.getSource().sendFeedback(prefix().append("Scanning containers in reach..."));
        return Command.SINGLE_SUCCESS;
    }

    private static int dump(CommandContext<FabricClientCommandSource> ctx) {
        Minecraft minecraft = ctx.getSource().getClient();
        StorageService service = StorageService.get();
        int usable = 0;
        int ignored = 0;
        int unreachable = 0;
        int missing = 0;
        for (ContainerRecord record : service.cache().records()) {
            if (record.isIgnored()) {
                ignored++;
            } else if (record.state() == ContainerState.UNREACHABLE) {
                unreachable++;
            } else if (record.state() == ContainerState.MISSING) {
                missing++;
            } else if (record.hasSnapshot()) {
                usable++;
            }
        }
        List<StorageIndex.Entry> entries = StorageIndex.aggregate(service.cache().records());
        long totalItems = entries.stream().mapToLong(StorageIndex.Entry::total).sum();
        ctx.getSource().sendFeedback(prefix().append(String.format(
                "%d item types, %d items in %d containers (%d ignored, %d unreachable, %d missing).",
                entries.size(), totalItems, usable, ignored, unreachable, missing)));
        service.debug("Dump: {} item types, {} items in {} containers", entries.size(), totalItems, usable);
        for (int i = 0; i < entries.size(); i++) {
            StorageIndex.Entry entry = entries.get(i);
            String name = displayName(minecraft, entry);
            String line = String.format("%,d  %s  (in %d)", entry.total(), name, entry.locations().size());
            service.debug("Dump: {}  {}  {}", entry.total(), entry.item().itemId(),
                    entry.item().hasComponents() ? entry.item().components() : "");
            if (i < DUMP_CHAT_LINES) {
                ctx.getSource().sendFeedback(Component.literal(line));
            }
        }
        if (entries.size() > DUMP_CHAT_LINES) {
            ctx.getSource().sendFeedback(Component.literal("... " + (entries.size() - DUMP_CHAT_LINES)
                    + " more in the log").withStyle(ChatFormatting.GRAY));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static String displayName(Minecraft minecraft, StorageIndex.Entry entry) {
        if (minecraft.level == null) {
            return entry.item().itemId();
        }
        ItemStack stack = ItemKeys.toStack(entry.item(), 1, minecraft.level.registryAccess());
        String name = stack.isEmpty() ? entry.item().itemId() : stack.getHoverName().getString();
        return entry.item().hasComponents() ? name + " *" : name;
    }

    static net.minecraft.network.chat.MutableComponent prefix() {
        return Component.literal("[Storage Terminal] ").withStyle(ChatFormatting.GOLD)
                .append(Component.empty().withStyle(ChatFormatting.RESET));
    }
}
