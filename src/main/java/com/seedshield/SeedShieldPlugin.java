package com.seedshield;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.logging.Level;

/**
 * SeedShield - Cryptographic structure seed protection for Paper/Spigot servers.
 *
 * Prevents seed cracking tools (chunkbase, SeedCrackerX, Structurecracker) from
 * determining structure locations by replacing each structure type's placement salt
 * with an irreversible SHA-256 derived value.
 *
 * Unlike simple salt randomization, SeedShield uses a secret key so that:
 * - Each structure type gets a unique cryptographic salt
 * - Cracking one structure type's salt cannot reveal others
 * - Without the secret key, salts cannot be reversed
 */
public class SeedShieldPlugin extends JavaPlugin implements Listener {

    private String secretKey;
    private List<String> enabledWorlds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("SeedShield enabled. Protected worlds: " + enabledWorlds);
    }

    private void loadConfiguration() {
        reloadConfig();
        FileConfiguration config = getConfig();

        // Generate a cryptographically secure random key on first run
        if (!config.contains("secret-key") || config.getString("secret-key", "").isEmpty()) {
            byte[] keyBytes = new byte[32];
            new SecureRandom().nextBytes(keyBytes);
            String key = HexFormat.of().formatHex(keyBytes);
            config.set("secret-key", key);
            saveConfig();
            getLogger().info("Generated new 256-bit secret key. Keep config.yml safe!");
        }

        secretKey = config.getString("secret-key");
        enabledWorlds = config.getStringList("enabled-worlds");

        if (enabledWorlds.isEmpty()) {
            enabledWorlds = List.of("world");
            config.set("enabled-worlds", enabledWorlds);
            saveConfig();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldInit(WorldInitEvent event) {
        World world = event.getWorld();
        String worldName = world.getName();

        if (!enabledWorlds.contains(worldName)) {
            return;
        }

        getLogger().info("Applying cryptographic structure seeds to world '" + worldName + "'...");

        try {
            applySecureSeeds(world, world.getSeed());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to apply secure seeds to world '" + worldName + "'", e);
        }
    }

    // ─── Core Logic ──────────────────────────────────────────────────────────────

    private void applySecureSeeds(World world, long worldSeed) throws Exception {
        // Reflection chain: CraftWorld → ServerLevel → ServerChunkCache → ChunkGeneratorStructureState
        Object serverLevel = world.getClass().getMethod("getHandle").invoke(world);
        Object chunkSource = serverLevel.getClass().getMethod("getChunkSource").invoke(serverLevel);

        Method getGenState = findMethodByReturnType(chunkSource.getClass(), "StructureState");
        if (getGenState == null) {
            getLogger().severe("Could not find ChunkGeneratorStructureState accessor. Is this Paper 1.21+?");
            return;
        }
        getGenState.setAccessible(true);
        Object structureState = getGenState.invoke(chunkSource);

        // Find the list of Holder<StructureSet> in the structure state
        List<?> holders = findStructureSetHolders(structureState);
        if (holders == null) {
            getLogger().severe("Could not locate structure set holders.");
            return;
        }

        // Find the StructurePlacement.salt field once
        Field saltField = null;
        int modified = 0;

        for (Object holder : holders) {
            try {
                Object structureSet = invokeValue(holder);
                if (structureSet == null) continue;

                String setName = resolveStructureSetName(holder);
                Object placement = invokePlacement(structureSet);
                if (placement == null) continue;

                // Lazy-init salt field from first placement we see
                if (saltField == null) {
                    saltField = findSaltField(placement.getClass());
                    if (saltField == null) {
                        getLogger().severe("Could not find 'salt' field in " + placement.getClass().getName());
                        return;
                    }
                    saltField.setAccessible(true);
                }

                int secureSalt = deriveStructureSalt(worldSeed, setName);
                saltField.setInt(placement, secureSalt);
                getLogger().info("  ✓ " + setName + " → salt=" + secureSalt);
                modified++;
            } catch (Exception e) {
                getLogger().warning("  ✗ Failed to process a structure set: " + e.getMessage());
            }
        }

        getLogger().info("Modified " + modified + " structure salts for world '" + world.getName() + "'");

        // Stronghold special handling: recalculate concentric ring positions
        recalculateStrongholdPositions(structureState, holders, worldSeed);
    }

    // ─── Stronghold Ring Recalculation ────────────────────────────────────────────

    private void recalculateStrongholdPositions(Object structureState, List<?> holders, long worldSeed) {
        try {
            // Find the ring positions cache (Map<ConcentricRingsStructurePlacement, CompletableFuture<List<ChunkPos>>>)
            Field ringPosField = findRingPositionsCacheField(structureState);
            if (ringPosField == null) {
                getLogger().warning("Could not find stronghold ring positions cache.");
                return;
            }
            ringPosField.setAccessible(true);
            Map<?, ?> ringMap = (Map<?, ?>) ringPosField.get(structureState);

            // Modify concentricRingsSeed to a secure value
            Field seedField = findConcentricRingsSeedField(structureState, worldSeed);
            if (seedField == null) {
                getLogger().warning("Could not find concentricRingsSeed field.");
                return;
            }
            seedField.setAccessible(true);
            long secureSeed = deriveLongSeed(worldSeed, "stronghold_rings");
            seedField.setLong(structureState, secureSeed);

            // Find generateRingPositions(Holder, ConcentricRingsStructurePlacement) method
            Method generateRings = findGenerateRingPositionsMethod(structureState);
            if (generateRings == null) {
                getLogger().warning("Could not find generateRingPositions method.");
                return;
            }
            generateRings.setAccessible(true);

            // Re-invoke for each ConcentricRingsStructurePlacement (strongholds)
            for (Object holder : holders) {
                try {
                    Object structureSet = invokeValue(holder);
                    if (structureSet == null) continue;
                    Object placement = invokePlacement(structureSet);
                    if (placement == null) continue;

                    if (placement.getClass().getSimpleName().contains("ConcentricRings")) {
                        Object future = generateRings.invoke(structureState, holder, placement);
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> mutableMap = (Map<Object, Object>) ringMap;
                        mutableMap.put(placement, future);
                        getLogger().info("  ✓ Stronghold ring positions recalculated");
                    }
                } catch (Exception e) {
                    getLogger().warning("  ✗ Stronghold recalculation error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to recalculate stronghold positions", e);
        }
    }

    // ─── Cryptographic Salt Derivation ────────────────────────────────────────────

    /**
     * Derives a deterministic, irreversible 32-bit salt from the secret key,
     * world seed, and structure type name using SHA-256.
     *
     * SHA-256(secretKey + ":" + worldSeed + ":" + structureName) → first 4 bytes → int
     */
    private int deriveStructureSalt(long worldSeed, String structureName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = secretKey + ":" + worldSeed + ":" + structureName;
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(hash, 0, 4).getInt();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    /**
     * Derives a 64-bit seed for stronghold ring position calculation.
     */
    private long deriveLongSeed(long worldSeed, String purpose) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = secretKey + ":" + worldSeed + ":" + purpose;
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(hash, 0, 8).getLong();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    // ─── Reflection Helpers ───────────────────────────────────────────────────────

    /** Resolve the registry name of a Holder<StructureSet> (e.g. "minecraft:villages") */
    private String resolveStructureSetName(Object holder) {
        try {
            Method unwrapKey = findMethod(holder.getClass(), "unwrapKey");
            if (unwrapKey != null) {
                unwrapKey.setAccessible(true);
                Object optional = unwrapKey.invoke(holder);
                if ((boolean) optional.getClass().getMethod("isPresent").invoke(optional)) {
                    Object resourceKey = optional.getClass().getMethod("get").invoke(optional);
                    // Try location() then identifier() (Paper uses Mojang mappings)
                    for (String name : new String[]{"location", "identifier"}) {
                        Method m = findMethod(resourceKey.getClass(), name);
                        if (m != null) {
                            m.setAccessible(true);
                            return m.invoke(resourceKey).toString();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "unknown_" + holder.hashCode();
    }

    /** Find the list of Holder<StructureSet> from ChunkGeneratorStructureState */
    private List<?> findStructureSetHolders(Object structureState) throws Exception {
        for (Field f : structureState.getClass().getDeclaredFields()) {
            if (List.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                Object val = f.get(structureState);
                if (val instanceof List<?> list && !list.isEmpty()) {
                    String typeName = list.get(0).getClass().getName();
                    if (typeName.contains("Holder") || typeName.contains("StructureSet")) {
                        return list;
                    }
                }
            }
        }
        return null;
    }

    /** Find the ring positions Map field */
    private Field findRingPositionsCacheField(Object structureState) throws Exception {
        for (Field f : getAllFields(structureState.getClass())) {
            if (Map.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                Object val = f.get(structureState);
                if (val instanceof Map<?, ?> map && !map.isEmpty()) {
                    Object firstKey = map.keySet().iterator().next();
                    if (firstKey.getClass().getSimpleName().contains("ConcentricRings")) {
                        return f;
                    }
                }
            }
        }
        return null;
    }

    /** Find the concentricRingsSeed long field */
    private Field findConcentricRingsSeedField(Object structureState, long worldSeed) throws Exception {
        for (Field f : getAllFields(structureState.getClass())) {
            if (f.getType() == long.class) {
                f.setAccessible(true);
                if (f.getName().toLowerCase().contains("ring") || f.getLong(structureState) == worldSeed) {
                    return f;
                }
            }
        }
        return null;
    }

    /** Find generateRingPositions(Holder, ConcentricRingsStructurePlacement) */
    private Method findGenerateRingPositionsMethod(Object structureState) {
        for (Method m : structureState.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() == 2) {
                Class<?>[] params = m.getParameterTypes();
                if (params[1].getSimpleName().contains("ConcentricRings")) {
                    return m;
                }
            }
        }
        return null;
    }

    /** Invoke Holder.value() */
    private Object invokeValue(Object holder) throws Exception {
        Method m = findMethod(holder.getClass(), "value");
        if (m == null) return null;
        m.setAccessible(true);
        return m.invoke(holder);
    }

    /** Invoke StructureSet.placement() */
    private Object invokePlacement(Object structureSet) throws Exception {
        Method m = findMethod(structureSet.getClass(), "placement");
        if (m == null) {
            for (Method method : structureSet.getClass().getMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType().getSimpleName().contains("Placement")) {
                    method.setAccessible(true);
                    return method.invoke(structureSet);
                }
            }
            return null;
        }
        m.setAccessible(true);
        return m.invoke(structureSet);
    }

    /** Find the int salt field in StructurePlacement hierarchy */
    private Field findSaltField(Class<?> clazz) {
        for (Field f : getAllFields(clazz)) {
            if (f.getName().equals("salt") && f.getType() == int.class) return f;
        }
        for (Field f : getAllFields(clazz)) {
            if (f.getName().toLowerCase().contains("salt") && f.getType() == int.class) return f;
        }
        return null;
    }

    /** Find a method by return type name containing a substring */
    private Method findMethodByReturnType(Class<?> clazz, String returnTypeContains) {
        for (Method m : clazz.getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType().getSimpleName().contains(returnTypeContains)) {
                return m;
            }
        }
        return null;
    }

    /** Find a no-arg method by name, searching up the hierarchy */
    private Method findMethod(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            for (Method m : current.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /** Get all fields from class and its superclasses */
    private List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            Collections.addAll(fields, current.getDeclaredFields());
            current = current.getSuperclass();
        }
        return fields;
    }
}
