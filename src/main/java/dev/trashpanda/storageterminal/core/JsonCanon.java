// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Canonical JSON: object keys sorted recursively, compact output. Used so that two
 * equal component patches always produce the same {@link ItemKey} string, whatever
 * order the encoder wrote the keys in.
 */
public final class JsonCanon {
    private JsonCanon() {
    }

    public static String canonical(JsonElement element) {
        return sorted(element).toString();
    }

    public static JsonElement sorted(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject in = element.getAsJsonObject();
            List<String> keys = new ArrayList<>(in.keySet());
            Collections.sort(keys);
            JsonObject out = new JsonObject();
            for (String key : keys) {
                out.add(key, sorted(in.get(key)));
            }
            return out;
        }
        if (element.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) {
                out.add(sorted(child));
            }
            return out;
        }
        return element;
    }
}
