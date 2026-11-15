/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.fastasyncworldedit.bukkit.util;

import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Access to Folia's schedulers without linking Folia-only types into the Bukkit plugin.
 * The reflective boundary keeps the same jar loadable on Spigot and older Paper servers.
 */
public final class FoliaScheduler {

    private static final Set<World> TEMPORARY_WORLDS = Collections.newSetFromMap(new IdentityHashMap<>());

    private FoliaScheduler() {
    }

    public static boolean isFolia() {
        return PaperSupport.isFolia();
    }

    public static boolean owns(World world, int chunkX, int chunkZ) {
        if (!isFolia()) {
            return true;
        }
        if (isTemporaryWorld(world)) {
            return true;
        }
        return (boolean) invoke(
                Bukkit.class,
                null,
                "isOwnedByCurrentRegion",
                new Class<?>[]{World.class, int.class, int.class},
                world,
                chunkX,
                chunkZ
        );
    }

    public static boolean owns(Entity entity) {
        if (!isFolia()) {
            return true;
        }
        if (isTemporaryWorld(entity.getWorld())) {
            return true;
        }
        return (boolean) invoke(
                Bukkit.class,
                null,
                "isOwnedByCurrentRegion",
                new Class<?>[]{Entity.class},
                entity
        );
    }

    public static boolean isGlobalThread() {
        if (!isFolia()) {
            return true;
        }
        return (boolean) invoke(Bukkit.class, null, "isGlobalTickThread", new Class<?>[0]);
    }

    public static <T> T callGlobal(ThrowingSupplier<T> action) {
        if (!isFolia() || isGlobalThread()) {
            return call(action);
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Object scheduler = scheduler("getGlobalRegionScheduler");
        Method run = method(schedulerType("getGlobalRegionScheduler"), "run", Plugin.class, Consumer.class);
        invoke(run, scheduler, WorldEditPlugin.getInstance(), (Consumer<Object>) ignored -> {
            try {
                result.complete(action.get());
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        return await(result);
    }

    public static void runGlobal(Runnable action) {
        if (!isFolia() || isGlobalThread()) {
            action.run();
            return;
        }
        Object scheduler = scheduler("getGlobalRegionScheduler");
        Method run = method(schedulerType("getGlobalRegionScheduler"), "run", Plugin.class, Consumer.class);
        invoke(run, scheduler, WorldEditPlugin.getInstance(), (Consumer<Object>) ignored -> action.run());
    }

    public static World world(Object serverLevel) {
        return (World) invoke(method(serverLevel.getClass(), "getWorld"), serverLevel);
    }

    /**
     * Register a non-ticking server world used by the adapter for offline generation.
     */
    public static void registerTemporaryWorld(World world) {
        synchronized (TEMPORARY_WORLDS) {
            TEMPORARY_WORLDS.add(world);
        }
    }

    public static void unregisterTemporaryWorld(World world) {
        synchronized (TEMPORARY_WORLDS) {
            TEMPORARY_WORLDS.remove(world);
        }
    }

    private static boolean isTemporaryWorld(World world) {
        synchronized (TEMPORARY_WORLDS) {
            return TEMPORARY_WORLDS.contains(world);
        }
    }

    public static <T> T callAtChunk(World world, int chunkX, int chunkZ, ThrowingSupplier<T> action) {
        return await(futureAtChunk(world, chunkX, chunkZ, action));
    }

    public static <T> CompletableFuture<T> futureAtChunk(World world, int chunkX, int chunkZ, ThrowingSupplier<T> action) {
        if (!isFolia() || owns(world, chunkX, chunkZ)) {
            try {
                return CompletableFuture.completedFuture(action.get());
            } catch (Throwable throwable) {
                return CompletableFuture.failedFuture(throwable);
            }
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Object scheduler = scheduler("getRegionScheduler");
        Method run = method(
                schedulerType("getRegionScheduler"),
                "run",
                Plugin.class,
                World.class,
                int.class,
                int.class,
                Consumer.class
        );
        invoke(run, scheduler, WorldEditPlugin.getInstance(), world, chunkX, chunkZ, (Consumer<Object>) ignored -> {
            try {
                result.complete(action.get());
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        return result;
    }

    public static void runAtChunk(World world, int chunkX, int chunkZ, Runnable action) {
        if (!isFolia() || owns(world, chunkX, chunkZ)) {
            action.run();
            return;
        }
        Object scheduler = scheduler("getRegionScheduler");
        Method run = method(
                schedulerType("getRegionScheduler"),
                "run",
                Plugin.class,
                World.class,
                int.class,
                int.class,
                Consumer.class
        );
        invoke(run, scheduler, WorldEditPlugin.getInstance(), world, chunkX, chunkZ,
                (Consumer<Object>) ignored -> action.run());
    }

    /**
     * Collect entities from loaded chunks while reading each chunk on its owning region.
     */
    public static List<Entity> getLoadedEntities(World world) {
        if (!isFolia()) {
            return world.getEntities();
        }
        Chunk[] loadedChunks = callGlobal(world::getLoadedChunks);
        List<CompletableFuture<List<Entity>>> tasks = new ArrayList<>(loadedChunks.length);
        for (Chunk chunk : loadedChunks) {
            int chunkX = chunk.getX();
            int chunkZ = chunk.getZ();
            tasks.add(futureAtChunk(world, chunkX, chunkZ, () -> {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    return List.of();
                }
                return List.of(world.getChunkAt(chunkX, chunkZ).getEntities());
            }));
        }
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture<?>[0])).join();
        List<Entity> entities = new ArrayList<>();
        for (CompletableFuture<List<Entity>> task : tasks) {
            entities.addAll(task.join());
        }
        return entities;
    }

    public static <T> T callAtEntity(Entity entity, ThrowingSupplier<T> action) {
        if (!isFolia() || owns(entity)) {
            return call(action);
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Object scheduler = invoke(
                Entity.class,
                entity,
                "getScheduler",
                new Class<?>[0]
        );
        Class<?> schedulerType = method(Entity.class, "getScheduler").getReturnType();
        Method run = method(schedulerType, "run", Plugin.class, Consumer.class, Runnable.class);
        Object scheduled = invoke(run, scheduler, WorldEditPlugin.getInstance(), (Consumer<Object>) ignored -> {
            try {
                result.complete(action.get());
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        }, (Runnable) () -> result.completeExceptionally(new IllegalStateException("Entity retired before its task ran")));
        if (scheduled == null) {
            result.completeExceptionally(new IllegalStateException("Entity scheduler rejected its task"));
        }
        return await(result);
    }

    public static void runAtEntity(Entity entity, Runnable action) {
        if (!isFolia() || owns(entity)) {
            action.run();
            return;
        }
        Object scheduler = invoke(Entity.class, entity, "getScheduler", new Class<?>[0]);
        Class<?> schedulerType = method(Entity.class, "getScheduler").getReturnType();
        Method run = method(schedulerType, "run", Plugin.class, Consumer.class, Runnable.class);
        Object scheduled = invoke(run, scheduler, WorldEditPlugin.getInstance(),
                (Consumer<Object>) ignored -> action.run(),
                (Runnable) () -> { });
        if (scheduled == null) {
            // The entity is already retired, so there is no owned entity state left to update.
        }
    }

    public static Object scheduleGlobal(Plugin plugin, Runnable action, long delayTicks, long periodTicks) {
        Object scheduler = scheduler("getGlobalRegionScheduler");
        Consumer<Object> task = ignored -> action.run();
        if (periodTicks > 0) {
            return invoke(method(schedulerType("getGlobalRegionScheduler"), "runAtFixedRate", Plugin.class,
                            Consumer.class, long.class, long.class), scheduler, plugin, task,
                    Math.max(1, delayTicks), periodTicks);
        }
        if (delayTicks > 0) {
            return invoke(method(schedulerType("getGlobalRegionScheduler"), "runDelayed", Plugin.class,
                            Consumer.class, long.class), scheduler, plugin, task, delayTicks);
        }
        return invoke(method(schedulerType("getGlobalRegionScheduler"), "run", Plugin.class, Consumer.class),
                scheduler, plugin, task);
    }

    public static Object scheduleAsync(Plugin plugin, Runnable action, long delayMillis, long periodMillis) {
        Object scheduler = scheduler("getAsyncScheduler");
        Consumer<Object> task = ignored -> action.run();
        if (periodMillis > 0) {
            return invoke(method(schedulerType("getAsyncScheduler"), "runAtFixedRate", Plugin.class,
                            Consumer.class, long.class, long.class, TimeUnit.class), scheduler, plugin, task,
                    Math.max(1, delayMillis), periodMillis, TimeUnit.MILLISECONDS);
        }
        if (delayMillis > 0) {
            return invoke(method(schedulerType("getAsyncScheduler"), "runDelayed", Plugin.class,
                            Consumer.class, long.class, TimeUnit.class), scheduler, plugin, task,
                    delayMillis, TimeUnit.MILLISECONDS);
        }
        return invoke(method(schedulerType("getAsyncScheduler"), "runNow", Plugin.class, Consumer.class),
                scheduler, plugin, task);
    }

    public static void cancel(Object scheduledTask) {
        if (scheduledTask != null) {
            invoke(method(scheduledTaskType(), "cancel"), scheduledTask);
        }
    }

    public static void cancelAll(Plugin plugin) {
        invoke(method(schedulerType("getGlobalRegionScheduler"), "cancelTasks", Plugin.class),
                scheduler("getGlobalRegionScheduler"), plugin);
        invoke(method(schedulerType("getAsyncScheduler"), "cancelTasks", Plugin.class),
                scheduler("getAsyncScheduler"), plugin);
    }

    private static Object scheduler(String accessor) {
        return invoke(Bukkit.class, null, accessor, new Class<?>[0]);
    }

    private static Class<?> schedulerType(String accessor) {
        return method(Bukkit.class, accessor).getReturnType();
    }

    private static Class<?> scheduledTaskType() {
        try {
            return Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Folia scheduled task API is unavailable", e);
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Folia scheduler API is unavailable: " + owner.getName() + "." + name, e);
        }
    }

    private static Object invoke(Class<?> owner, Object receiver, String name, Class<?>[] parameterTypes, Object... args) {
        return invoke(method(owner, name, parameterTypes), receiver, args);
    }

    private static Object invoke(Method method, Object receiver, Object... args) {
        try {
            return method.invoke(receiver, args);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not access Folia scheduler API " + method, e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Folia scheduler call failed: " + method, cause);
        }
    }

    private static <T> T call(ThrowingSupplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the owning Folia scheduler", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
