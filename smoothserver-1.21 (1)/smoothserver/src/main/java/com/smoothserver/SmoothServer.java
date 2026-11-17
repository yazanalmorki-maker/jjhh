package com.smoothserver;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SmoothServer implements DedicatedServerModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("smoothserver");

    // ---- Settings (edit and rebuild) ----
    static final double HIGH_MSPT = 45.0;      // above this: lower simulation distance
    static final double LOW_MSPT = 30.0;       // below this: restore simulation distance
    static final int MIN_SIM_DISTANCE = 4;
    static final int CLEANUP_INTERVAL = 600;   // ticks (30 s)
    static final int ITEM_MAX_AGE = 3000;      // ticks (2.5 min) before dropped items are removed
    static final int MAX_MOBS_PER_CHUNK = 24;  // extra unnamed, non-tamed mobs get removed

    private final long[] samples = new long[100];
    private long tickStart;
    private int baseSimDistance = -1;

    @Override
    public void onInitializeServer() {
        ServerTickEvents.START_SERVER_TICK.register(s -> tickStart = System.nanoTime());
        ServerTickEvents.END_SERVER_TICK.register(this::onEndTick);
        LOGGER.info("Smooth Server loaded.");
    }

    private void onEndTick(MinecraftServer server) {
        long ticks = server.getTicks();
        samples[(int) (ticks % samples.length)] = System.nanoTime() - tickStart;

        if (ticks % 100 == 0) adaptSimulationDistance(server);
        if (ticks % CLEANUP_INTERVAL == 0) cleanup(server);
    }

    private void adaptSimulationDistance(MinecraftServer server) {
        long total = 0;
        for (long s : samples) total += s;
        double avgMs = (total / (double) samples.length) / 1_000_000.0;

        PlayerManager pm = server.getPlayerManager();
        int current = pm.getSimulationDistance();
        if (baseSimDistance < 0) baseSimDistance = current;

        if (avgMs > HIGH_MSPT && current > MIN_SIM_DISTANCE) {
            pm.setSimulationDistance(current - 1);
            LOGGER.info("High load ({} ms/tick): simulation distance {} -> {}", String.format("%.1f", avgMs), current, current - 1);
        } else if (avgMs < LOW_MSPT && current < baseSimDistance) {
            pm.setSimulationDistance(current + 1);
            LOGGER.info("Load OK ({} ms/tick): simulation distance {} -> {}", String.format("%.1f", avgMs), current, current + 1);
        }
    }

    private void cleanup(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            List<Entity> toRemove = new ArrayList<>();
            Map<ChunkPos, Integer> mobCounts = new HashMap<>();

            for (Entity e : world.iterateEntities()) {
                if (e instanceof ItemEntity item) {
                    if (item.getItemAge() > ITEM_MAX_AGE) toRemove.add(item);
                } else if (e instanceof MobEntity mob && isRemovable(mob)) {
                    int n = mobCounts.merge(mob.getChunkPos(), 1, Integer::sum);
                    if (n > MAX_MOBS_PER_CHUNK) toRemove.add(mob);
                }
            }
            for (Entity e : toRemove) e.discard();
            if (!toRemove.isEmpty()) {
                LOGGER.info("Cleaned {} entities in {}", toRemove.size(), world.getRegistryKey().getValue());
            }
        }
    }

    private static boolean isRemovable(MobEntity mob) {
        if (mob.isPersistent() || mob.hasCustomName() || mob.isLeashed()) return false;
        if (mob instanceof MerchantEntity) return false;
        return !(mob instanceof TameableEntity t && t.isTamed());
    }
}
