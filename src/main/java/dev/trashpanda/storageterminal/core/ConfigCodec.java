// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The config file: JSON with {@code //} comments explaining every key. Reading never fails: a
 * missing, misspelled or out-of-range value falls back to its default and is reported.
 */
public final class ConfigCodec {
    /** The settings read, plus one line per value that had to fall back to its default. */
    public record ReadResult(Config config, List<String> problems) {
    }

    private ConfigCodec() {
    }

    public static ReadResult read(String text) {
        List<String> problems = new ArrayList<>();
        JsonObject o;
        try {
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setStrictness(Strictness.LENIENT);
            JsonElement root = JsonParser.parseReader(reader);
            o = root.isJsonObject() ? root.getAsJsonObject() : new JsonObject();
            if (!root.isJsonObject()) {
                problems.add("the file is not a JSON object; using all defaults");
            }
        } catch (RuntimeException e) {
            problems.add("the file could not be read (" + e.getMessage() + "); using all defaults");
            o = new JsonObject();
        }
        Config d = Config.DEFAULTS;
        Config config = new Config(
                number(o, "reachMargin", d.reachMargin(), 0.0, 2.0, problems),
                (int) number(o, "openCooldownTicks", d.openCooldownTicks(), 0, 100, problems),
                (int) number(o, "maxOpensPerSecond", d.maxOpensPerSecond(), 1, 20, problems),
                (long) number(o, "syncTimeoutMs", d.syncTimeoutMs(), 200, 30_000, problems),
                (long) number(o, "minRefreshIntervalMs", d.minRefreshIntervalMs(), 0, 3_600_000, problems),
                bool(o, "includeTrappedChests", d.includeTrappedChests(), problems),
                bool(o, "abortOnDamage", d.abortOnDamage(), problems),
                bool(o, "depositIncludesHotbar", d.depositIncludesHotbar(), problems),
                sort(o, d.defaultSort(), problems),
                bool(o, "debugLogging", d.debugLogging(), problems));
        return new ReadResult(config, problems);
    }

    public static String write(Config c) {
        return String.join("\n",
                "// Storage Terminal settings. Remove a line (or the whole file) to get its default back.",
                "{",
                "  // Stay this many blocks inside your reach when opening containers, as a safety margin.",
                "  // 0 to 2, default 0.25.",
                "  \"reachMargin\": " + c.reachMargin() + ",",
                "",
                "  // Minimum game ticks between opening two containers. 0 to 100, default 4.",
                "  \"openCooldownTicks\": " + c.openCooldownTicks() + ",",
                "",
                "  // Hard limit on containers opened per second. 1 to 20, default 4.",
                "  // Raise it only if the server allows fast interaction.",
                "  \"maxOpensPerSecond\": " + c.maxOpensPerSecond() + ",",
                "",
                "  // How long to wait for a container's contents after opening it, in milliseconds,",
                "  // before giving up on it. Raise it on laggy servers. 200 to 30000, default 1000.",
                "  \"syncTimeoutMs\": " + c.syncTimeoutMs() + ",",
                "",
                "  // When the terminal opens, containers looked into less than this long ago are not",
                "  // opened again (the Scan button always rescans). Milliseconds, 0 to 3600000, default 30000.",
                "  \"minRefreshIntervalMs\": " + c.minRefreshIntervalMs() + ",",
                "",
                "  // Use trapped chests. WARNING: opening a trapped chest sends a redstone signal,",
                "  // so anything wired to it (doors, TNT, ...) will trigger. Default true.",
                "  \"includeTrappedChests\": " + c.includeTrappedChests() + ",",
                "",
                "  // Stop the current scan, take or store when you take damage. Default true.",
                "  \"abortOnDamage\": " + c.abortOnDamage() + ",",
                "",
                "  // Let \"Deposit all\" store the hotbar too. Default false (hotbar is kept).",
                "  \"depositIncludesHotbar\": " + c.depositIncludesHotbar() + ",",
                "",
                "  // Sort order of the terminal: COUNT, NAME, MOD or RECENT. Remembers your last choice.",
                "  \"defaultSort\": \"" + c.defaultSort() + "\",",
                "",
                "  // Extra log output and the debug commands /storageterminal scan and dump. Default false.",
                "  \"debugLogging\": " + c.debugLogging(),
                "}",
                "");
    }

    private static double number(JsonObject o, String key, double fallback, double min, double max, List<String> problems) {
        if (!o.has(key)) {
            return fallback;
        }
        try {
            double value = o.get(key).getAsDouble();
            if (Double.isNaN(value) || value < min || value > max) {
                problems.add(key + " must be between " + fmt(min) + " and " + fmt(max) + "; using " + fmt(fallback));
                return fallback;
            }
            return value;
        } catch (RuntimeException e) {
            problems.add(key + " is not a number; using " + fmt(fallback));
            return fallback;
        }
    }

    private static boolean bool(JsonObject o, String key, boolean fallback, List<String> problems) {
        if (!o.has(key)) {
            return fallback;
        }
        JsonElement e = o.get(key);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean();
        }
        problems.add(key + " must be true or false; using " + fallback);
        return fallback;
    }

    private static String sort(JsonObject o, String fallback, List<String> problems) {
        if (!o.has("defaultSort")) {
            return fallback;
        }
        String raw = o.get("defaultSort").isJsonPrimitive() ? o.get("defaultSort").getAsString() : "";
        for (SortOrder order : SortOrder.values()) {
            if (order.name().equalsIgnoreCase(raw)) {
                return order.name();
            }
        }
        problems.add("defaultSort must be COUNT, NAME, MOD or RECENT; using " + fallback);
        return fallback;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%s", v);
    }
}
