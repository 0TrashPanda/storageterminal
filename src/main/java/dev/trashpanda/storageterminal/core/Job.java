// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

/**
 * A unit of background work (scan, take, put; later crafting), driven one tick at a time by
 * {@link JobQueue}. Only one job runs at a time.
 */
public interface Job {
    enum Status {
        RUNNING,
        DONE,
        FAILED,
        CANCELLED
    }

    String name();

    /** Advances the job by one tick. Called only while this job is the current one. */
    Status tick();

    /**
     * Stops the job and leaves the world clean (no open menus, nothing on the cursor).
     * {@link #tick()} is not called again afterwards.
     */
    void cancel(String reason);
}
