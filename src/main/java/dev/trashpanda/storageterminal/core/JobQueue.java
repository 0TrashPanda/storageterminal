// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import java.util.ArrayDeque;
import java.util.Deque;

/** Runs {@link Job}s strictly one after another. Not thread-safe; tick it from the client thread. */
public final class JobQueue {
    /** Told when a job leaves the queue, whatever the reason. */
    public interface Listener {
        void finished(Job job, Job.Status status);
    }

    private final Deque<Job> pending = new ArrayDeque<>();
    private final Listener listener;
    private Job current;

    public JobQueue(Listener listener) {
        this.listener = listener;
    }

    public void enqueue(Job job) {
        pending.addLast(job);
    }

    public boolean isBusy() {
        return current != null || !pending.isEmpty();
    }

    public Job current() {
        return current;
    }

    /** The running job if it matches, else the first waiting one that matches, else null. */
    public <T extends Job> T currentOrPending(Class<T> type) {
        if (type.isInstance(current)) {
            return type.cast(current);
        }
        for (Job job : pending) {
            if (type.isInstance(job)) {
                return type.cast(job);
            }
        }
        return null;
    }

    public void tick() {
        if (current == null) {
            current = pending.pollFirst();
            if (current == null) {
                return;
            }
        }
        Job job = current;
        Job.Status status;
        try {
            status = job.tick();
        } catch (RuntimeException e) {
            current = null;
            job.cancel("error: " + e);
            listener.finished(job, Job.Status.FAILED);
            throw e;
        }
        if (status != Job.Status.RUNNING) {
            current = null;
            listener.finished(job, status);
        }
    }

    /** Cancels the running job and drops the queued ones, but only jobs that match. */
    public void cancelIf(java.util.function.Predicate<Job> which, String reason) {
        Job job = current;
        if (job != null && which.test(job)) {
            current = null;
            job.cancel(reason);
            listener.finished(job, Job.Status.CANCELLED);
        }
        java.util.Iterator<Job> it = pending.iterator();
        while (it.hasNext()) {
            Job waiting = it.next();
            if (which.test(waiting)) {
                it.remove();
                listener.finished(waiting, Job.Status.CANCELLED);
            }
        }
    }

    /** Cancels the running job and drops everything queued. */
    public void cancelAll(String reason) {
        Job job = current;
        current = null;
        if (job != null) {
            job.cancel(reason);
            listener.finished(job, Job.Status.CANCELLED);
        }
        while (!pending.isEmpty()) {
            listener.finished(pending.pollFirst(), Job.Status.CANCELLED);
        }
    }
}
