// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

/** What we know about a cached container. */
public enum ContainerState {
    /** Snapshot taken this session. */
    FRESH,
    /** Snapshot loaded from disk or older than the refresh interval. */
    STALE,
    /** Last open attempt failed (blocked, timed out, refused by the server). */
    UNREACHABLE,
    /** The block is no longer a supported container. */
    MISSING
}
