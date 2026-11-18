// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for the small helpers: positions, slots, JSON canonical form, reach, rate limit, job queue. */
class SmallPartsTest {
    @Test
    void doubleChestCanonicalPositionIsTheSmallerOne() {
        BlockPosition a = new BlockPosition(5, 64, 2);
        BlockPosition b = new BlockPosition(5, 64, 1);
        assertEquals(b, BlockPosition.min(a, b));
        assertEquals(b, BlockPosition.min(b, a));
        assertEquals(new BlockPosition(-1, 0, 9), BlockPosition.min(new BlockPosition(-1, 0, 9), new BlockPosition(0, 0, 0)));
    }

    @Test
    void emptySlotNormalizes() {
        assertTrue(new SlotStack(ItemKey.of("minecraft:dirt"), 0).isEmpty());
        assertTrue(new SlotStack(null, 5).isEmpty());
        assertEquals(SlotStack.EMPTY, new SlotStack(null, 5));
    }

    @Test
    void canonicalJsonIgnoresKeyOrder() {
        String a = JsonCanon.canonical(JsonParser.parseString("{\"b\":1,\"a\":{\"y\":[{\"q\":1,\"p\":2}],\"x\":true}}"));
        String b = JsonCanon.canonical(JsonParser.parseString("{\"a\":{\"x\":true,\"y\":[{\"p\":2,\"q\":1}]},\"b\":1}"));
        assertEquals(a, b);
        assertEquals("{\"a\":{\"x\":true,\"y\":[{\"p\":2,\"q\":1}]},\"b\":1}", a);
    }

    @Test
    void reachUsesNearestPointOfTheBlock() {
        BlockPosition block = new BlockPosition(0, 0, 0);
        // Eye 4 blocks east of the block's east face (x = 1) at mid height.
        assertEquals(16.0, Reach.distanceSqToBlock(5.0, 0.5, 0.5, block), 1e-9);
        assertEquals(0.0, Reach.distanceSqToBlock(0.5, 0.5, 0.5, block), 1e-9);
        assertTrue(Reach.isWithin(5.0, 0.5, 0.5, block, 4.5, 0.25));
        assertFalse(Reach.isWithin(5.3, 0.5, 0.5, block, 4.5, 0.25));
        assertFalse(Reach.isWithin(0.5, 0.5, 0.5, block, 0.2, 0.25), "margin larger than range never reaches");
    }

    @Test
    void rateLimiterEnforcesGapAndPerSecondCap() {
        RateLimiter limiter = new RateLimiter(2, 3);
        assertTrue(limiter.canOpen(0, 0));
        limiter.recordOpen(0, 0);
        assertFalse(limiter.canOpen(1, 50), "cooldown");
        assertTrue(limiter.canOpen(2, 100));
        limiter.recordOpen(2, 100);
        limiter.recordOpen(4, 200);
        assertFalse(limiter.canOpen(6, 300), "3 opens within one second");
        assertTrue(limiter.canOpen(6, 1000), "first open has left the window");
    }

    @Test
    void jobQueueRunsOneJobAtATimeAndCancelsCleanly() {
        List<String> log = new ArrayList<>();
        JobQueue queue = new JobQueue((job, status) -> log.add(job.name() + ":" + status));
        CountingJob first = new CountingJob("first", 2, log);
        CountingJob second = new CountingJob("second", 5, log);
        queue.enqueue(first);
        queue.enqueue(second);

        queue.tick();
        queue.tick();
        assertEquals(List.of("first:DONE"), log);
        queue.tick();
        assertEquals(1, second.ticks);

        queue.cancelAll("test");
        assertEquals(List.of("first:DONE", "second cancelled: test", "second:CANCELLED"), log);
        assertFalse(queue.isBusy());
        queue.tick();
        assertEquals(1, second.ticks, "a cancelled job is never ticked again");
    }

    private static final class CountingJob implements Job {
        private final String name;
        private final int length;
        private final List<String> log;
        int ticks;

        CountingJob(String name, int length, List<String> log) {
            this.name = name;
            this.length = length;
            this.log = log;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Status tick() {
            ticks++;
            return ticks >= length ? Status.DONE : Status.RUNNING;
        }

        @Override
        public void cancel(String reason) {
            log.add(name + " cancelled: " + reason);
        }
    }
}
