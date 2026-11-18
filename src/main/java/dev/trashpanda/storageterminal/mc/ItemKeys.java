// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.trashpanda.storageterminal.StorageTerminal;
import dev.trashpanda.storageterminal.core.ItemKey;
import dev.trashpanda.storageterminal.core.JsonCanon;
import dev.trashpanda.storageterminal.core.SlotStack;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Converts between Minecraft item stacks and core {@link ItemKey}s, using the game's own item codec. */
public final class ItemKeys {
    private ItemKeys() {
    }

    public static SlotStack toSlot(ItemStack stack, RegistryAccess registries) {
        if (stack.isEmpty()) {
            return SlotStack.EMPTY;
        }
        return new SlotStack(toKey(stack, registries), stack.getCount());
    }

    public static ItemKey toKey(ItemStack stack, RegistryAccess registries) {
        try {
            JsonObject json = ItemStack.CODEC
                    .encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), stack.copyWithCount(1))
                    .getOrThrow()
                    .getAsJsonObject();
            String id = json.get("id").getAsString();
            JsonElement components = json.get("components");
            boolean none = components == null || (components.isJsonObject() && components.getAsJsonObject().isEmpty());
            return new ItemKey(id, none ? "" : JsonCanon.canonical(components));
        } catch (RuntimeException e) {
            // Should not happen for synced stacks; keep the item distinguishable rather than crash.
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            StorageTerminal.LOGGER.warn("Could not encode {}, using a fallback key", id, e);
            return new ItemKey(id, "{\"storageterminal:unencodable\":\"" + stack.getComponentsPatch().hashCode() + "\"}");
        }
    }

    /** Rebuilds a stack for display. Returns an empty stack if the item no longer exists. */
    public static ItemStack toStack(ItemKey key, int count, RegistryAccess registries) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("id", key.itemId());
            json.addProperty("count", Math.max(1, Math.min(count, 99)));
            if (key.hasComponents()) {
                json.add("components", JsonParser.parseString(key.components()));
            }
            return ItemStack.CODEC
                    .parse(registries.createSerializationContext(JsonOps.INSTANCE), json)
                    .result()
                    .orElse(ItemStack.EMPTY);
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }
}
