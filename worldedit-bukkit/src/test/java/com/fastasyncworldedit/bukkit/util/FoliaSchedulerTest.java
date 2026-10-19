package com.fastasyncworldedit.bukkit.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FoliaSchedulerTest {

    @Test
    void preservesInlineFallbackWhenFoliaIsAbsent() {
        assertFalse(FoliaScheduler.isFolia());
        AtomicInteger calls = new AtomicInteger();

        assertEquals("chunk", FoliaScheduler.callAtChunk(null, -3, 7, () -> {
            calls.incrementAndGet();
            return "chunk";
        }));
        assertEquals("entity", FoliaScheduler.callAtEntity(null, () -> {
            calls.incrementAndGet();
            return "entity";
        }));
        assertEquals("global", FoliaScheduler.callGlobal(() -> {
            calls.incrementAndGet();
            return "global";
        }));
        FoliaScheduler.runAtChunk(null, 0, 0, calls::incrementAndGet);
        FoliaScheduler.runAtEntity(null, calls::incrementAndGet);
        FoliaScheduler.runGlobal(calls::incrementAndGet);

        assertEquals(6, calls.get());
    }
}
