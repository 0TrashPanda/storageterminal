// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import java.util.Locale;

/** Item count text: compact for grid slots ({@code 1.2k}), exact with separators for tooltips. */
public final class CountFormat {
    private CountFormat() {
    }

    /** At most 4 characters for counts below 10 billion: 999, 1.2k, 12k, 999k, 1.2M, 12M. */
    public static String compact(long count) {
        if (count < 1_000) {
            return Long.toString(count);
        }
        if (count < 10_000) {
            return oneDecimal(count / 100) + "k";
        }
        if (count < 1_000_000) {
            return (count / 1_000) + "k";
        }
        if (count < 10_000_000) {
            return oneDecimal(count / 100_000) + "M";
        }
        if (count < 1_000_000_000) {
            return (count / 1_000_000) + "M";
        }
        return (count / 1_000_000_000) + "G";
    }

    public static String exact(long count) {
        return String.format(Locale.ROOT, "%,d", count);
    }

    /** {@code tenths} = value * 10, rounded down; drops a trailing ".0". */
    private static String oneDecimal(long tenths) {
        long whole = tenths / 10;
        long fraction = tenths % 10;
        return fraction == 0 ? Long.toString(whole) : whole + "." + fraction;
    }
}
