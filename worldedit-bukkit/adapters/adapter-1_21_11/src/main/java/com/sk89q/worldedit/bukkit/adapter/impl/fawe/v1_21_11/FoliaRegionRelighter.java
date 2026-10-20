package com.sk89q.worldedit.bukkit.adapter.impl.fawe.v1_21_11;

import com.fastasyncworldedit.core.extent.processor.lighting.Relighter;
import com.fastasyncworldedit.bukkit.util.FoliaScheduler;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Folia-only incremental native relighter.
 *
 * The normal NMSRelighter reads light from many chunks off-thread while
 * waiting for owning-region futures. This can indefinitely stall FAWE's
 * per-player async edit queue (including undo/redo).
 *
 * Instead, lightEngine.checkBlock runs exclusively on each chunk's owning
 * region. Work is sliced across ticks and never waited on by the FAWE worker.
 * The server's native light engine handles sky/block propagation.
 */
public final class FoliaRegionRelighter implements Relighter {

    private static final int CHECKS_PER_TICK = 256;

    private final World world;
    private final ServerLevel level;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Long, Integer> sections = new HashMap<>();
    private final AtomicBoolean submitted = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();

    public FoliaRegionRelighter(World world) {
        this.world = world;
        this.level = ((CraftWorld) world).getHandle();
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    @Override
    public synchronized boolean addChunk(int chunkX, int chunkZ, byte[] skipReason, int bitmask) {
        if (submitted.get()) {
            return false;
        }
        // The bitmask identifies changed sections. A zero mask should not
        // silently skip relighting, so treat it as all sections.
        sections.merge(chunkKey(chunkX, chunkZ), bitmask == 0 ? -1 : bitmask, (a, b) -> a | b);
        return true;
    }

    @Override
    public synchronized void addLightUpdate(int x, int y, int z) {
        if (submitted.get()) {
            return;
        }
        int section = (y - world.getMinHeight()) >> 4;
        if (section >= 0 && section < 32) {
            sections.merge(chunkKey(x >> 4, z >> 4), 1 << section, (a, b) -> a | b);
        }
    }

    /**
     * Never delete native light arrays on Folia: it would blank chunk lighting
     * before the region-owned light engine gets a chance to recalculate them.
     */
    @Override
    public void removeLighting() {
        // Native light updates are incremental.
    }

    @Override
    public void removeAndRelight(boolean sky) {
        fixLightingSafe(sky);
    }

    @Override
    public void fixSkyLighting() {
        fixLightingSafe(true);
    }

    @Override
    public void fixBlockLighting() {
        fixLightingSafe(false);
    }

    @Override
    public void fixLightingSafe(boolean sky) {
        if (!submitted.compareAndSet(false, true)) {
            return;
        }

        Map<Long, Integer> captured;
        synchronized (this) {
            captured = new HashMap<>(sections);
            sections.clear();
        }

        // Only schedule work. Never block FAWE's async edit/undo/redo worker.
        for (Map.Entry<Long, Integer> entry : captured.entrySet()) {
            int chunkX = (int) (entry.getKey() >> 32);
            int chunkZ = (int) (long) entry.getKey();
            ChunkLightTask task = new ChunkLightTask(chunkX, chunkZ, entry.getValue());
            schedule(task);
        }
    }

    private void schedule(ChunkLightTask task) {
        Bukkit.getRegionScheduler().runDelayed(
                WorldEditPlugin.getInstance(), world, task.chunkX, task.chunkZ,
                ignored -> executeBatch(task),
                1L
        );
    }

    private void executeBatch(ChunkLightTask task) {
        if (!FoliaScheduler.owns(world, task.chunkX, task.chunkZ)) {
            throw new IllegalStateException("Folia lighting task ran outside owning region");
        }
        if (!world.isChunkLoaded(task.chunkX, task.chunkZ)) {
            // Never force-generate or asynchronously load a missing chunk.
            return;
        }

        int processed = 0;
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();

        while (processed < CHECKS_PER_TICK && task.section < 32) {
            if ((task.mask & (1 << task.section)) == 0) {
                task.section++;
                task.index = 0;
                continue;
            }
            int y = minHeight + task.section * 16 + (task.index >> 8);
            if (y >= maxHeight) {
                task.section = 32;
                break;
            }
            int x = task.chunkX * 16 + (task.index & 15);
            int z = task.chunkZ * 16 + ((task.index >> 4) & 15);
            level.getChunkSource().getLightEngine().checkBlock(new BlockPos(x, y, z));
            task.index++;
            processed++;
            if (task.index >= 4096) {
                task.index = 0;
                task.section++;
            }
        }

        if (task.section < 32) {
            schedule(task);
        }
    }

    @Override
    public synchronized void clear() {
        sections.clear();
    }

    @Override
    public synchronized boolean isEmpty() {
        return sections.isEmpty();
    }

    @Override
    public ReentrantLock getLock() {
        return lock;
    }

    @Override
    public boolean isFinished() {
        return finished.get();
    }

    @Override
    public void close() {
        // Scheduled native lighting continues on its region independently.
        finished.set(true);
    }

    private static final class ChunkLightTask {
        final int chunkX;
        final int chunkZ;
        final int mask;
        int section;
        int index;
        ChunkLightTask(int chunkX, int chunkZ, int mask) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.mask = mask;
        }
    }
}
