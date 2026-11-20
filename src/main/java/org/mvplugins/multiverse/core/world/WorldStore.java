package org.mvplugins.multiverse.core.world;

import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import io.vavr.control.Option;
import jakarta.inject.Inject;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jvnet.hk2.annotations.Service;
import org.mvplugins.multiverse.core.utils.CaseInsensitiveStringMap;
import org.mvplugins.multiverse.core.world.key.WorldKeyOrName;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
final class WorldStore {

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Only loaded worlds, i.e. will be empty if all worlds are unloaded.
     */
    private final List<LoadedMultiverseWorld> loadedList;

    /**
     * Only unloaded worlds, i.e. will be empty if all worlds are loaded.
     */
    private final List<MultiverseWorld> unloadedList;

    /**
     * Contains single reference to all worlds, either loaded or unloaded ref depending on the world's current status.
     */
    private final List<MultiverseWorld> worldList;

    /**
     * Maps all key and key to loaded ref.
     */
    private final Map<String, LoadedMultiverseWorld> loadedMap;

    /**
     * Maps all key and key to unloaded ref.
     */
    private final Map<String, MultiverseWorld> unloadedMap;

    /**
     * Maps colorless alias to world key. As alias may not be unique, this is a multimap.
     */
    private final Multimap<String, String> aliasMap;

    @Inject
    private WorldStore() {
        this.loadedList = new ArrayList<>();
        this.unloadedList = new ArrayList<>();
        this.worldList = new ArrayList<>();

        this.loadedMap = new CaseInsensitiveStringMap<>();
        this.unloadedMap = new CaseInsensitiveStringMap<>();
        this.aliasMap = HashMultimap.create();
    }

    void putUnloadedWorld(MultiverseWorld world) {
        if (world instanceof LoadedMultiverseWorld) {
            throw new IllegalArgumentException("Loaded world cannot be put in unloaded map");
        }

        lock.writeLock().lock();
        try {
            unloadedMap.put(world.getKey().toString(), world);
            unloadedMap.put(world.getName(), world);

            unloadedList.add(world);
            worldList.add(world);
        } finally {
            lock.writeLock().unlock();
        }
    }

    void putLoadedWorld(LoadedMultiverseWorld world) {
        lock.writeLock().lock();
        try {
            loadedMap.put(world.getKey().toString(), world);
            loadedMap.put(world.getName(), world);

            unloadedList.remove(unloadedMap.get(world.getKey().toString()));
            worldList.remove(unloadedMap.get(world.getKey().toString()));

            loadedList.add(world);
            worldList.add(world);
        } finally {
            lock.writeLock().unlock();
        }
    }

    void removeWorld(MultiverseWorld world) {
        lock.writeLock().lock();
        try {
            LoadedMultiverseWorld loadedRef = loadedMap.get(world.getKey().toString());
            MultiverseWorld unloadedRef = unloadedMap.get(world.getKey().toString());

            // remove from list
            unloadedList.remove(unloadedRef);
            loadedList.remove(loadedRef);
            worldList.remove(unloadedRef);
            worldList.remove(loadedRef);

            // remove from maps
            loadedMap.remove(world.getKey().toString());
            loadedMap.remove(world.getName());

            unloadedMap.remove(world.getKey().toString());
            unloadedMap.remove(world.getName());

            // remove alias
            aliasMap.remove(world.getColourlessAlias(), world.getKey().toString());
        } finally {
            lock.writeLock().unlock();
        }
    }

    void removeLoadedWorld(LoadedMultiverseWorld world) {
        lock.writeLock().lock();
        try {
            // remove from list
            loadedList.remove(world);
            worldList.remove(world);

            // remove from maps
            loadedMap.remove(world.getKey().toString());
            loadedMap.remove(world.getName());

            // Add back unloaded
            MultiverseWorld unloadedRef = unloadedMap.get(world.getKey().toString());
            if (unloadedRef != null) {
                unloadedList.add(unloadedRef);
                worldList.add(unloadedRef);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    void changeLegacyWorldName(@Nullable String oldName, @Nullable String newName, @NotNull NamespacedKey worldKey) {
        if (Objects.equals(oldName, newName)) {
            return;
        }
        lock.writeLock().lock();
        try {
            String key = worldKey.toString();
            updateLegacyWorldName(loadedMap, oldName, newName, key);
            updateLegacyWorldName(unloadedMap, oldName, newName, key);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private <T extends MultiverseWorld> void updateLegacyWorldName(
            Map<String, T> map, @Nullable String oldName, @Nullable String newName, String key) {
        T world = map.get(key);
        if (world == null) {
            return;
        }
        if (oldName != null && !key.equalsIgnoreCase(oldName)) {
            map.remove(oldName, world);
        }
        if (newName != null) {
            map.put(newName, world);
        }
    }

    void changeAlias(@Nullable String oldAlias, @Nullable String newAlias, @NotNull NamespacedKey worldKey) {
        if (Objects.equals(oldAlias, newAlias)) {
            // nothing changed, ignore
            return;
        }
        lock.writeLock().lock();
        try {
            if (!Strings.isNullOrEmpty(oldAlias)) {
                aliasMap.remove(oldAlias.toLowerCase(Locale.ROOT), worldKey.toString());
            }
            if (!Strings.isNullOrEmpty(newAlias)) {
                aliasMap.put(newAlias.toLowerCase(Locale.ROOT), worldKey.toString());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Unmodifiable
    @NotNull
    List<MultiverseWorld> getWorlds() {
        lock.readLock().lock();
        try {
            return List.copyOf(worldList);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Unmodifiable
    @NotNull
    List<LoadedMultiverseWorld> getLoadedWorlds() {
        lock.readLock().lock();
        try {
            return List.copyOf(loadedList);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Unmodifiable
    @NotNull
    List<MultiverseWorld> getUnloadedWorlds() {
        lock.readLock().lock();
        try {
            return List.copyOf(unloadedList);
        } finally {
            lock.readLock().unlock();
        }
    }

    @NotNull
    Option<MultiverseWorld> getWorld(@Nullable WorldKeyOrName keyOrName) {
        return keyOrName == null
                ? Option.none()
                : getWorld(keyOrName.usableKey()).orElse(() -> getWorld(keyOrName.usableName()));
    }

    @NotNull
    Option<MultiverseWorld> getWorld(@Nullable NamespacedKey worldKey) {
        return Option.of(worldKey).map(NamespacedKey::toString).flatMap(this::getWorld);
    }

    @NotNull
    Option<MultiverseWorld> getWorld(@Nullable String worldKeyString) {
        lock.readLock().lock();
        try {
            return Option.of((MultiverseWorld) loadedMap.get(worldKeyString))
                    .orElse(() -> Option.of(unloadedMap.get(worldKeyString)));
        } finally {
            lock.readLock().unlock();
        }
    }

    boolean isWorld(@Nullable WorldKeyOrName keyOrName) {
        return keyOrName != null && (isWorld(keyOrName.usableKey()) || isWorld(keyOrName.usableName()));
    }

    boolean isWorld(@Nullable NamespacedKey worldKey) {
        return getWorld(worldKey).isDefined();
    }

    boolean isWorld(@Nullable String worldKeyString) {
        return getWorld(worldKeyString).isDefined();
    }

    @NotNull
    Option<LoadedMultiverseWorld> getLoadedWorld(@Nullable WorldKeyOrName keyOrName) {
        return keyOrName == null
                ? Option.none()
                : getLoadedWorld(keyOrName.usableKey()).orElse(() -> getLoadedWorld(keyOrName.usableName()));
    }

    @NotNull
    Option<LoadedMultiverseWorld> getLoadedWorld(@Nullable NamespacedKey worldKey) {
        return Option.of(worldKey).map(NamespacedKey::toString).flatMap(this::getLoadedWorld);
    }

    @NotNull
    Option<LoadedMultiverseWorld> getLoadedWorld(@Nullable String worldKeyString) {
        lock.readLock().lock();
        try {
            return Option.of(loadedMap.get(worldKeyString))
                    .filter(MultiverseWorld::isLoaded);
        } finally {
            lock.readLock().unlock();
        }
    }

    boolean isLoadedWorld(@Nullable WorldKeyOrName keyOrName) {
        return keyOrName != null && (isLoadedWorld(keyOrName.usableKey()) || isLoadedWorld(keyOrName.usableName()));
    }

    boolean isLoadedWorld(@Nullable NamespacedKey worldKey) {
        return getLoadedWorld(worldKey).isDefined();
    }

    boolean isLoadedWorld(@Nullable String worldKeyString) {
        return getLoadedWorld(worldKeyString).isDefined();
    }

    @NotNull
    Option<MultiverseWorld> getUnloadedWorld(@Nullable WorldKeyOrName keyOrName) {
        return keyOrName == null
                ? Option.none()
                : getUnloadedWorld(keyOrName.usableKey()).orElse(() -> getUnloadedWorld(keyOrName.usableName()));
    }

    @NotNull
    Option<MultiverseWorld> getUnloadedWorld(@Nullable NamespacedKey worldKey) {
        return Option.of(worldKey).map(NamespacedKey::toString).flatMap(this::getUnloadedWorld);
    }

    @NotNull
    Option<MultiverseWorld> getUnloadedWorld(@Nullable String worldKeyString) {
        lock.readLock().lock();
        try {
            return Option.of(unloadedMap.get(worldKeyString))
                    .filter(world -> !world.isLoaded());
        } finally {
            lock.readLock().unlock();
        }
    }

    boolean isUnloadedWorld(@Nullable WorldKeyOrName keyOrName) {
        return keyOrName != null && (isUnloadedWorld(keyOrName.usableKey()) || isUnloadedWorld(keyOrName.usableName()));
    }

    boolean isUnloadedWorld(@Nullable NamespacedKey worldKey) {
        return getUnloadedWorld(worldKey).isDefined();
    }

    boolean isUnloadedWorld(@Nullable String worldKeyString) {
        return getUnloadedWorld(worldKeyString).isDefined();
    }

    @NotNull
    Option<MultiverseWorld> getUnloadedWorldRef(@Nullable WorldKeyOrName keyOrName) {
        return keyOrName == null
                ? Option.none()
                : getUnloadedWorldRef(keyOrName.usableKey()).orElse(() -> getUnloadedWorldRef(keyOrName.usableName()));
    }

    @NotNull
    Option<MultiverseWorld> getUnloadedWorldRef(@Nullable NamespacedKey worldKey) {
        return Option.of(worldKey).map(NamespacedKey::toString).flatMap(this::getUnloadedWorldRef);
    }

    @NotNull
    Option<MultiverseWorld> getUnloadedWorldRef(@Nullable String worldKeyString) {
        lock.readLock().lock();
        try {
            return Option.of(unloadedMap.get(worldKeyString));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Nullable
    String translateAlias(@Nullable String worldNameOrAlias) {
        if (Strings.isNullOrEmpty(worldNameOrAlias)) {
            return null;
        }
        lock.readLock().lock();
        try {
            //TODO: Not sure if we should fail if there is multiple alias of the same name, but for now just return the first one
            return aliasMap.get(worldNameOrAlias.toLowerCase(Locale.ROOT)).stream()
                    .findFirst()
                    .orElse(worldNameOrAlias);
        } finally {
            lock.readLock().unlock();
        }
    }
}
