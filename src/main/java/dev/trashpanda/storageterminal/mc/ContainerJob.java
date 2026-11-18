// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import dev.trashpanda.storageterminal.core.Job;

/** A job that opens containers one at a time; the menu hooks talk to its current session. */
interface ContainerJob extends Job {
    /** The session in progress, or null between containers. */
    ContainerSession activeSession();
}
