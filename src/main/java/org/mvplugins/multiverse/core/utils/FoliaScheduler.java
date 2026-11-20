package org.mvplugins.multiverse.core.utils;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import io.papermc.lib.PaperLib;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Region-aware scheduler abstraction for Folia with transparent fallback to the standard Bukkit scheduler
 * on Paper, Purpur, Spigot, and MockBukkit servers.
 */
public final class FoliaScheduler {

    private static final boolean FOLIA = detectFolia();
    private static volatile Plugin defaultPlugin;

    private FoliaScheduler() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    /**
     * Registers the plugin instance used by convenience helpers that do not take an explicit {@link Plugin}.
     *
     * @param plugin The Multiverse-Core plugin instance.
     */
    public static void setDefaultPlugin(@Nullable Plugin plugin) {
        defaultPlugin = plugin;
    }

    /**
     * Gets the default plugin instance if registered.
     *
     * @return The plugin instance, or null if not yet initialized.
     */
    public static @Nullable Plugin getDefaultPlugin() {
        return defaultPlugin;
    }

    /**
     * Checks whether the server is running Folia (regionized multithreading).
     *
     * @return True if running on Folia, false otherwise.
     */
    public static boolean isFolia() {
        return FOLIA;
    }

    /**
     * Checks whether the current thread owns the region containing the given {@link Location}.
     * Always returns true on non-Folia servers.
     *
     * @param location The location to check.
     * @return True if owned by the current region thread or not running on Folia.
     */
    public static boolean isOwnedByCurrentRegion(@NotNull Location location) {
        if (!FOLIA) {
            return true;
        }
        return FoliaImpl.isOwnedByCurrentRegion(location);
    }

    /**
     * Checks whether the current thread owns the region of the given {@link Entity}.
     * Always returns true on non-Folia servers.
     *
     * @param entity The entity to check.
     * @return True if owned by the current region thread or not running on Folia.
     */
    public static boolean isOwnedByCurrentRegion(@NotNull Entity entity) {
        if (!FOLIA) {
            return true;
        }
        return FoliaImpl.isOwnedByCurrentRegion(entity);
    }

    /**
     * Checks whether the current thread is Folia's global region tick thread.
     * Always returns true on non-Folia servers.
     *
     * @return True if running on the global tick thread or not running on Folia.
     */
    public static boolean isGlobalTickThread() {
        if (!FOLIA) {
            return true;
        }
        return FoliaImpl.isGlobalTickThread();
    }

    /**
     * Runs a task on the global region scheduler (Folia) or main server thread (non-Folia).
     *
     * @param plugin The owning plugin.
     * @param task   The task to execute.
     * @return A cancellable {@link TaskHandle}.
     */
    public static @NotNull TaskHandle runGlobal(@NotNull Plugin plugin, @NotNull Runnable task) {
        if (FOLIA) {
            return FoliaImpl.runGlobal(plugin, task);
        }
        return wrapBukkit(Bukkit.getScheduler().runTask(plugin, task));
    }

    /**
     * Runs a task immediately if not on Folia or already on the global tick thread;
     * otherwise dispatches it to Folia's global region scheduler.
     *
     * @param plugin The owning plugin (may be null to use the default plugin).
     * @param task   The task to execute.
     */
    public static void runGlobalNowOrSchedule(@Nullable Plugin plugin, @NotNull Runnable task) {
        if (!FOLIA || isGlobalTickThread()) {
            task.run();
            return;
        }
        Plugin target = plugin != null ? plugin : defaultPlugin;
        if (target == null || !target.isEnabled()) {
            task.run();
            return;
        }
        FoliaImpl.executeGlobal(target, task);
    }

    /**
     * Runs a delayed task on the global region scheduler (Folia) or main server thread (non-Folia).
     *
     * @param plugin     The owning plugin.
     * @param task       The task to execute.
     * @param delayTicks Delay in ticks (clamped to at least 1 on Folia).
     * @return A cancellable {@link TaskHandle}.
     */
    public static @NotNull TaskHandle runGlobalDelayed(
            @NotNull Plugin plugin,
            @NotNull Runnable task,
            long delayTicks) {
        if (FOLIA) {
            return FoliaImpl.runGlobalDelayed(plugin, task, Math.max(1L, delayTicks));
        }
        return wrapBukkit(Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(0L, delayTicks)));
    }

    /**
     * Runs a task on the region thread owning the given {@link Location} (Folia)
     * or on the main server thread (non-Folia).
     *
     * @param plugin   The owning plugin.
     * @param location The target location.
     * @param task     The task to execute.
     * @return A cancellable {@link TaskHandle}.
     */
    public static @NotNull TaskHandle runAtLocation(
            @NotNull Plugin plugin,
            @NotNull Location location,
            @NotNull Runnable task) {
        if (FOLIA) {
            return FoliaImpl.runAtLocation(plugin, location, task);
        }
        return wrapBukkit(Bukkit.getScheduler().runTask(plugin, task));
    }

    /**
     * Runs a task on the entity's owning region thread (Folia) or main server thread (non-Folia).
     *
     * @param plugin The owning plugin.
     * @param entity The target entity.
     * @param task   The task to execute.
     * @return A cancellable {@link TaskHandle}, or a no-op handle if the entity is already retired.
     */
    public static @NotNull TaskHandle runAtEntity(
            @NotNull Plugin plugin,
            @NotNull Entity entity,
            @NotNull Runnable task) {
        if (FOLIA) {
            return FoliaImpl.runAtEntity(plugin, entity, task);
        }
        return wrapBukkit(Bukkit.getScheduler().runTask(plugin, task));
    }

    /**
     * Runs a task immediately if not on Folia or already on the entity's owning region thread;
     * otherwise dispatches it to the entity's scheduler.
     *
     * @param plugin The owning plugin (may be null to use the default plugin).
     * @param entity The target entity.
     * @param task   The task to execute.
     */
    public static void runAtEntityNowOrSchedule(
            @Nullable Plugin plugin,
            @NotNull Entity entity,
            @NotNull Runnable task) {
        if (!FOLIA || isOwnedByCurrentRegion(entity)) {
            task.run();
            return;
        }
        Plugin target = plugin != null ? plugin : defaultPlugin;
        if (target == null || !target.isEnabled()) {
            task.run();
            return;
        }
        FoliaImpl.runAtEntity(target, entity, task);
    }

    /**
     * Runs a delayed task on the entity's owning region thread (Folia) or main server thread (non-Folia).
     *
     * @param plugin     The owning plugin.
     * @param entity     The target entity.
     * @param task       The task to execute.
     * @param delayTicks Delay in ticks (clamped to at least 1 on Folia).
     * @return A cancellable {@link TaskHandle}, or a no-op handle if the entity is already retired.
     */
    public static @NotNull TaskHandle runAtEntityDelayed(
            @NotNull Plugin plugin,
            @NotNull Entity entity,
            @NotNull Runnable task,
            long delayTicks) {
        if (FOLIA) {
            return FoliaImpl.runAtEntityDelayed(plugin, entity, task, Math.max(1L, delayTicks));
        }
        return wrapBukkit(Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(0L, delayTicks)));
    }

    /**
     * Runs a repeating task on the entity's owning region thread (Folia) or main server thread (non-Folia).
     * The consumer receives a {@link TaskHandle} that can cancel the repeating task from inside the callback.
     *
     * @param plugin            The owning plugin.
     * @param entity            The target entity.
     * @param task              Consumer invoked each tick with its cancellable {@link TaskHandle}.
     * @param retired           Optional callback invoked if the entity retires/disconnects before completion.
     * @param initialDelayTicks Initial delay in ticks (clamped to at least 1 on Folia).
     * @param periodTicks       Period in ticks (clamped to at least 1 on Folia).
     * @return A cancellable {@link TaskHandle}.
     */
    public static @NotNull TaskHandle runAtEntityFixedRate(
            @NotNull Plugin plugin,
            @NotNull Entity entity,
            @NotNull Consumer<TaskHandle> task,
            @Nullable Runnable retired,
            long initialDelayTicks,
            long periodTicks) {
        if (FOLIA) {
            return FoliaImpl.runAtEntityFixedRate(
                    plugin, entity, task, retired, Math.max(1L, initialDelayTicks), Math.max(1L, periodTicks));
        }
        final BukkitTask[] holder = new BukkitTask[1];
        TaskHandle handle = () -> {
            if (holder[0] != null) {
                holder[0].cancel();
            }
        };
        holder[0] = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> task.accept(handle),
                Math.max(0L, initialDelayTicks),
                Math.max(1L, periodTicks));
        return handle;
    }

    /**
     * Runs a task asynchronously using Folia's async scheduler or Bukkit's async scheduler.
     *
     * @param plugin The owning plugin.
     * @param task   The task to execute.
     * @return A cancellable {@link TaskHandle}.
     */
    public static @NotNull TaskHandle runAsync(@NotNull Plugin plugin, @NotNull Runnable task) {
        if (FOLIA) {
            return FoliaImpl.runAsync(plugin, task);
        }
        return wrapBukkit(Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
    }

    /**
     * Teleports an entity asynchronously using native {@link Entity#teleportAsync(Location)} on Folia
     * and {@link PaperLib#teleportAsync(Entity, Location)} on non-Folia servers.
     *
     * @param entity   The entity to teleport.
     * @param location The destination location.
     * @return A future completing with true if the teleport succeeded.
     */
    public static @NotNull CompletableFuture<Boolean> teleportAsync(
            @NotNull Entity entity,
            @NotNull Location location) {
        if (FOLIA) {
            return FoliaImpl.teleportAsync(entity, location);
        }
        return PaperLib.teleportAsync(entity, location);
    }

    private static @NotNull TaskHandle wrapBukkit(@NotNull BukkitTask task) {
        return task::cancel;
    }

    /**
     * A lightweight handle for cancelling scheduled tasks across both Folia and Bukkit schedulers.
     */
    @FunctionalInterface
    public interface TaskHandle {
        /**
         * Cancels this scheduled task if it has not yet completed.
         */
        void cancel();
    }

    /**
     * Lazy-loaded Folia API implementation so Paper/Folia scheduler classes are only resolved on Folia.
     */
    private static final class FoliaImpl {

        private static final TaskHandle NO_OP = () -> {};

        private static boolean isOwnedByCurrentRegion(@NotNull Location location) {
            return Bukkit.isOwnedByCurrentRegion(location);
        }

        private static boolean isOwnedByCurrentRegion(@NotNull Entity entity) {
            return Bukkit.isOwnedByCurrentRegion(entity);
        }

        private static boolean isGlobalTickThread() {
            return Bukkit.isGlobalTickThread();
        }

        private static void executeGlobal(@NotNull Plugin plugin, @NotNull Runnable task) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
        }

        private static @NotNull TaskHandle runGlobal(@NotNull Plugin plugin, @NotNull Runnable task) {
            ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> task.run());
            return scheduled::cancel;
        }

        private static @NotNull TaskHandle runGlobalDelayed(
                @NotNull Plugin plugin,
                @NotNull Runnable task,
                long delayTicks) {
            ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler()
                    .runDelayed(plugin, ignored -> task.run(), delayTicks);
            return scheduled::cancel;
        }

        private static @NotNull TaskHandle runAtLocation(
                @NotNull Plugin plugin,
                @NotNull Location location,
                @NotNull Runnable task) {
            ScheduledTask scheduled = Bukkit.getRegionScheduler().run(plugin, location, ignored -> task.run());
            return scheduled::cancel;
        }

        private static @NotNull TaskHandle runAtEntity(
                @NotNull Plugin plugin,
                @NotNull Entity entity,
                @NotNull Runnable task) {
            ScheduledTask scheduled = entity.getScheduler().run(plugin, ignored -> task.run(), null);
            return scheduled != null ? scheduled::cancel : NO_OP;
        }

        private static @NotNull TaskHandle runAtEntityDelayed(
                @NotNull Plugin plugin,
                @NotNull Entity entity,
                @NotNull Runnable task,
                long delayTicks) {
            ScheduledTask scheduled = entity.getScheduler()
                    .runDelayed(plugin, ignored -> task.run(), null, delayTicks);
            return scheduled != null ? scheduled::cancel : NO_OP;
        }

        private static @NotNull TaskHandle runAtEntityFixedRate(
                @NotNull Plugin plugin,
                @NotNull Entity entity,
                @NotNull Consumer<TaskHandle> task,
                @Nullable Runnable retired,
                long initialDelayTicks,
                long periodTicks) {
            ScheduledTask scheduled = entity.getScheduler().runAtFixedRate(
                    plugin,
                    scheduledTask -> task.accept(scheduledTask::cancel),
                    retired,
                    initialDelayTicks,
                    periodTicks);
            return scheduled != null ? scheduled::cancel : NO_OP;
        }

        private static @NotNull TaskHandle runAsync(@NotNull Plugin plugin, @NotNull Runnable task) {
            ScheduledTask scheduled = Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run());
            return scheduled::cancel;
        }

        private static @NotNull CompletableFuture<Boolean> teleportAsync(
                @NotNull Entity entity,
                @NotNull Location location) {
            return entity.teleportAsync(location);
        }
    }
}
