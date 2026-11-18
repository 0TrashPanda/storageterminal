// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

/**
 * Tags a user can put on a container. Stored as strings in the cache so new tags
 * (INPUT, OUTPUT, SMELTER, ...) can be added later without a schema change; unknown
 * strings read from disk are kept as they are.
 */
public enum ContainerTag {
    /** Never open, never use for take or put. */
    IGNORE;

    public String id() {
        return name();
    }
}
