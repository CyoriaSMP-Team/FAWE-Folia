package com.fastasyncworldedit.bukkit.util;

import com.fastasyncworldedit.core.util.TaskManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public class BukkitTaskManager extends TaskManager {

    private final Plugin plugin;
    private final AtomicInteger nextFoliaTaskId = new AtomicInteger();
    private final ConcurrentMap<Integer, FoliaTask> foliaTasks = new ConcurrentHashMap<>();

    public BukkitTaskManager(final Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int repeat(@Nonnull final Runnable runnable, final int interval) {
        if (FoliaScheduler.isFolia()) {
            return scheduleFolia(runnable, interval, interval, false);
        }
        return this.plugin.getServer().getScheduler().scheduleSyncRepeatingTask(this.plugin, runnable, interval, interval);
    }

    @Override
    public int repeatAsync(@Nonnull final Runnable runnable, final int interval) {
        if (FoliaScheduler.isFolia()) {
            return scheduleFolia(runnable, interval, interval, true);
        }
        return this.plugin.getServer().getScheduler().scheduleAsyncRepeatingTask(this.plugin, runnable, interval, interval);
    }

    @Override
    public void async(@Nonnull final Runnable runnable) {
        if (FoliaScheduler.isFolia()) {
            scheduleFolia(runnable, 0, 0, true);
            return;
        }
        this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, runnable).getTaskId();
    }

    @Override
    public void task(@Nonnull final Runnable runnable) {
        if (FoliaScheduler.isFolia()) {
            scheduleFolia(runnable, 0, 0, false);
            return;
        }
        this.plugin.getServer().getScheduler().runTask(this.plugin, runnable).getTaskId();
    }

    @Override
    public void later(@Nonnull final Runnable runnable, final int delay) {
        if (FoliaScheduler.isFolia()) {
            scheduleFolia(runnable, delay, 0, false);
            return;
        }
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, runnable, delay).getTaskId();
    }

    @Override
    public void laterAsync(@Nonnull final Runnable runnable, final int delay) {
        if (FoliaScheduler.isFolia()) {
            scheduleFolia(runnable, delay, 0, true);
            return;
        }
        this.plugin.getServer().getScheduler().runTaskLaterAsynchronously(this.plugin, runnable, delay);
    }

    @Override
    public int schedule(@Nonnull final Runnable runnable, final long delay, final long period) {
        if (FoliaScheduler.isFolia()) {
            return scheduleFolia(runnable, delay, period, false);
        }
        return this.plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
                this.plugin, runnable, delay, period
        );
    }

    @Override
    public void cancel(final int task) {
        if (task != -1) {
            FoliaTask foliaTask = foliaTasks.remove(task);
            if (foliaTask != null) {
                foliaTask.cancel();
            } else if (!FoliaScheduler.isFolia()) {
                Bukkit.getScheduler().cancelTask(task);
            }
        }
    }

    private int scheduleFolia(Runnable runnable, long delay, long period, boolean async) {
        int taskId = nextFoliaTaskId.incrementAndGet();
        boolean repeating = period > 0;
        FoliaTask handle = new FoliaTask();
        foliaTasks.put(taskId, handle);
        Runnable wrapped = () -> {
            try {
                runnable.run();
            } finally {
                if (!repeating) {
                    foliaTasks.remove(taskId, handle);
                }
            }
        };
        try {
            Object scheduled = async
                    ? FoliaScheduler.scheduleAsync(this.plugin, wrapped, delay * 50L, period * 50L)
                    : FoliaScheduler.scheduleGlobal(this.plugin, wrapped, delay, period);
            handle.set(scheduled);
            return taskId;
        } catch (RuntimeException e) {
            foliaTasks.remove(taskId, handle);
            throw e;
        }
    }

    private static final class FoliaTask {
        private volatile Object scheduled;
        private volatile boolean cancelled;

        private void set(Object scheduled) {
            this.scheduled = scheduled;
            if (cancelled) {
                FoliaScheduler.cancel(scheduled);
            }
        }

        private void cancel() {
            cancelled = true;
            FoliaScheduler.cancel(scheduled);
        }
    }

}
