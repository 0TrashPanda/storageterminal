// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

/** Sort orders of the terminal grid, in the order the sort button cycles through them. */
public enum SortOrder {
    COUNT("Count"),
    NAME("Name"),
    MOD("Mod"),
    RECENT("Recent");

    private final String label;

    SortOrder(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public SortOrder next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** Parses a config value; unknown values give {@link #COUNT}. */
    public static SortOrder parse(String value) {
        for (SortOrder order : values()) {
            if (order.name().equalsIgnoreCase(value)) {
                return order;
            }
        }
        return COUNT;
    }
}
