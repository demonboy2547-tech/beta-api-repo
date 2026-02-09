package org.loioh.craftmate.scanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds a lightweight "scene snapshot" for realtime 1:1 responses.
 *
 * Anti-cheat / non-wallhack policy:
 *  - Primary signal is focus target (raycast) = what player is looking at
 *  - Nearby entities are filtered to those the player can plausibly perceive:
 *      - in front of the player (FOV cone) AND line-of-sight
 *      - OR hostile and very close (awareness)
 *  - We do NOT scan blocks behind walls.
 */
public final class SceneScanner {

    private SceneScanner() {}

    /**
     * Scanner v2 payload: higher-quality perception that remains non-cheaty.
     *
     * Snapshot mode (deep=false): called frequently (e.g. every 1s)
     * Deep mode (deep=true): called less frequently (e.g. every 4s)
     */
    public static JsonObject buildSceneSnapshotV2(Player player, int radiusBlocks, boolean deep, int maxEntities, int maxPois) {
        Minecraft mc = Minecraft.getInstance();
        Level level = player.level();

        JsonObject root = new JsonObject();
        root.addProperty("schema", "craftmate_scene_v2");
        root.addProperty("schema_version", 2);
        root.addProperty("ts_ms", System.currentTimeMillis());
        root.addProperty("deep", deep);

        // -------------------------
        // Player state
        // -------------------------
        JsonObject ps = new JsonObject();
        ps.addProperty("health", (int) Math.floor(player.getHealth()));
        ps.addProperty("hunger", player.getFoodData() != null ? player.getFoodData().getFoodLevel() : 0);
        ps.addProperty("saturation", player.getFoodData() != null ? round1(player.getFoodData().getSaturationLevel()) : 0);
        ps.addProperty("is_daytime", level.isDay());
        ps.addProperty("is_sneaking", player.isShiftKeyDown());
        ps.addProperty("is_sprinting", player.isSprinting());
        ps.addProperty("is_on_ground", player.onGround());
        ps.addProperty("is_underwater", player.isUnderWater());
        ps.addProperty("y", Mth.floor(player.getY()));

        JsonObject pos = new JsonObject();
        pos.addProperty("x", round1(player.getX()));
        pos.addProperty("y", round1(player.getY()));
        pos.addProperty("z", round1(player.getZ()));
        ps.add("pos", pos);

        try {
            JsonObject rot = new JsonObject();
            rot.addProperty("yaw", round1(player.getYRot()));
            rot.addProperty("pitch", round1(player.getXRot()));
            ps.add("rot", rot);
        } catch (Throwable ignored) {}

        // Dimension + biome
        ResourceLocation dim = level.dimension().location();
        ps.addProperty("dimension", dim != null ? dim.toString() : "unknown");
        ps.addProperty("biome", safeBiome(level, player));
        
        // gamemode / abilities (helps backend avoid false "danger" spam in creative)
        try {
            if (mc != null && mc.gameMode != null) {
                String gm = String.valueOf(mc.gameMode.getPlayerMode().getName());
                ps.addProperty("gamemode", gm);
            }
        } catch (Throwable ignored) {}
root.add("player_state", ps);

        // -------------------------
        // World context (light/time/weather)
        // -------------------------
        JsonObject world = new JsonObject();
        try {
            long tod = level.getDayTime() % 24000;
            world.addProperty("time_of_day", (int) tod);
        } catch (Throwable ignored) {}
        try {
            world.addProperty("is_raining", level.isRaining());
            world.addProperty("is_thundering", level.isThundering());
        } catch (Throwable ignored) {}
        try {
            int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, player.blockPosition());
            int block = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, player.blockPosition());
            world.addProperty("light_sky", sky);
            world.addProperty("light_block", block);
        } catch (Throwable ignored) {}
        root.add("world", world);

        // -------------------------
        // Focus + intention
        // -------------------------
        JsonObject focus = buildFocus(mc, player);
        if (focus != null) root.add("focus", focus);

        // Activity signals (derived; not wallhack)
        root.add("activity", ActivityTracker.buildActivityJson(player));

        // Update tracker once per snapshot tick (cheap)
        ActivityTracker.onTick(player, ActivityTracker.focusKeyFromFocusJson(focus));

        // -------------------------
        // Nearby perceivable entities
        // -------------------------
        JsonArray ents = buildNearbyEntities(player, radiusBlocks, Math.max(10, Math.min(maxEntities, 80)));
        JsonObject nearby = new JsonObject();
        nearby.add("entities", ents);

        // danger summary
        nearby.add("danger", summarizeDanger(ents));

        // POI blocks (deep only)
        if (deep) {
            nearby.add("pois", scanPoisVisible(player, Math.min(radiusBlocks, 18), Math.max(5, Math.min(maxPois, 80))));
        }
        root.add("nearby", nearby);

        // -------------------------
        // Recent events (summarized) - uses existing cache
        // -------------------------
        try {
            String[] ev = org.loioh.craftmate.core.Core.getEvents(player.getUUID());
            JsonArray arr = new JsonArray();
            int n = Math.min(ev != null ? ev.length : 0, 10);
            for (int i = 0; i < n; i++) {
                String e = ev[i];
                if (e == null) continue;
                // keep compact
                if (e.length() > 120) e = e.substring(0, 120);
                arr.add(e);
            }
            root.add("recent_events", arr);
        } catch (Throwable ignored) {}

        return root;
    }

    private static JsonObject buildFocus(Minecraft mc, Player player) {
        try {
            HitResult hr = mc.hitResult;
            if (hr == null) return null;

            JsonObject focus = new JsonObject();
            focus.addProperty("distance", round1(hr.getLocation().distanceTo(player.getEyePosition())));

            if (hr.getType() == HitResult.Type.ENTITY && hr instanceof EntityHitResult ehr) {
                Entity e = ehr.getEntity();
                ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
                focus.addProperty("type", "entity");
                focus.addProperty("id", id != null ? id.toString() : "unknown");
                focus.addProperty("name", safeName(e));
                focus.addProperty("is_hostile", (e instanceof Monster));

                // held item (if mob/player)
                try {
                    if (e instanceof Mob mob) {
                        ResourceLocation held = BuiltInRegistries.ITEM.getKey(mob.getMainHandItem().getItem());
                        if (held != null) focus.addProperty("held_item", held.toString());
                    }
                } catch (Throwable ignored) {}

                return focus;
            }

            if (hr.getType() == HitResult.Type.BLOCK && hr instanceof BlockHitResult bhr) {
                BlockPos p = bhr.getBlockPos();
                ResourceLocation bid = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(p).getBlock());
                focus.addProperty("type", "block");
                focus.addProperty("id", bid != null ? bid.toString() : "unknown");
                focus.addProperty("x", p.getX());
                focus.addProperty("y", p.getY());
                focus.addProperty("z", p.getZ());
                return focus;
            }

        } catch (Throwable ignored) {
        }
        return null;
    }

    private static JsonArray buildNearbyEntities(Player player, int radiusBlocks, int limit) {
        JsonArray out = new JsonArray();
        Level level = player.level();

        // Collect entities in radius
        double r = Math.max(6, Math.min(radiusBlocks, 64));
        Vec3 center = player.position();

        List<Entity> all = new ArrayList<>(level.getEntities(player, player.getBoundingBox().inflate(r)));
        // Sort by distance
        all.sort(Comparator.comparingDouble(e -> e.distanceToSqr(player)));

        // FOV cone filter
        Vec3 look = player.getLookAngle();
        double cosHalfFov = Math.cos(Math.toRadians(60.0)); // ~120deg total

        int lim = Math.max(5, Math.min(limit, 120));
        for (Entity e : all) {
            if (e == null || e == player) continue;
            if (!e.isAlive()) continue;

            double dist = Math.sqrt(e.distanceToSqr(player));
            boolean hostile = e instanceof Monster;

            // awareness zone: hostile very close even if not in LoS
            boolean awareness = hostile && dist <= 6.5;

            // in front cone
            Vec3 dir = e.position().subtract(center);
            double len = dir.length();
            if (len < 0.0001) continue;
            Vec3 nd = dir.scale(1.0 / len);
            boolean inFov = look.dot(nd) >= cosHalfFov;

            boolean los = false;
            try {
                los = player.hasLineOfSight(e);
            } catch (Throwable ignored) {}

            // Only include if perceivable
            if (!(awareness || (inFov && los))) continue;

            JsonObject jo = new JsonObject();
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            jo.addProperty("id", id != null ? id.toString() : "unknown");
            jo.addProperty("name", safeName(e));
            jo.addProperty("distance", round1(dist));
            jo.addProperty("is_hostile", hostile);

            // Include a hint if holding an item (helps with modded entities too)
            try {
                if (e instanceof Mob mob) {
                    ResourceLocation held = BuiltInRegistries.ITEM.getKey(mob.getMainHandItem().getItem());
                    if (held != null) jo.addProperty("held_item", held.toString());
                }
            } catch (Throwable ignored) {}

            out.add(jo);
            if (out.size() >= lim) break;
        }

        return out;
    }

    private static JsonObject summarizeDanger(JsonArray ents) {
        JsonObject d = new JsonObject();
        int hostile = 0;
        double nearestHostile = 999.0;
        for (int i = 0; i < ents.size(); i++) {
            try {
                JsonObject e = ents.get(i).getAsJsonObject();
                boolean h = e.has("is_hostile") && e.get("is_hostile").getAsBoolean();
                if (!h) continue;
                hostile++;
                double dist = e.has("distance") ? e.get("distance").getAsDouble() : 999.0;
                if (dist < nearestHostile) nearestHostile = dist;
            } catch (Throwable ignored) {}
        }
        d.addProperty("hostile_count", hostile);
        if (nearestHostile < 999.0) d.addProperty("nearest_hostile", round1(nearestHostile));
        return d;
    }

    private static String safeBiome(Level level, Player player) {
        String biomeStr = "unknown";
        try {
            biomeStr = level.getBiome(player.blockPosition()).unwrapKey()
                    .map(k -> {
                        try {
                            ResourceLocation loc = k.location();
                            return loc != null ? loc.toString() : "unknown";
                        } catch (Throwable t) {
                            return "unknown";
                        }
                    })
                    .orElse("unknown");
        } catch (Throwable ignored) {}
        return biomeStr;
    }

    /** Deep-only: scan for interesting blocks within a small range, but only if visible via ray check. */
    private static JsonArray scanPoisVisible(Player player, int range, int maxPois) {
        JsonArray out = new JsonArray();
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        int r = Math.max(4, Math.min(range, 18));
        int ySpan = 4;

        BlockPos base = player.blockPosition();
        int found = 0;

        // iterate a modest volume; filter aggressively by "interesting" ids
        for (int dy = -ySpan; dy <= ySpan; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (found >= maxPois) return out;
                    BlockPos p = base.offset(dx, dy, dz);
                    double dist = Math.sqrt(p.distSqr(base));
                    if (dist > r) continue;
                    try {
                        var state = level.getBlockState(p);
                        if (state.isAir()) continue;
                        ResourceLocation bid = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                        String id = bid != null ? bid.toString() : "";
                        if (!isInterestingBlockId(id)) continue;

                        // visibility ray: only include if ray hits this block
                        Vec3 center = Vec3.atCenterOf(p);
                        // Forge/MC mappings: ClipContext is under net.minecraft.world.level (not world.phys)
                        HitResult hr = level.clip(new net.minecraft.world.level.ClipContext(
                                eye, center,
                                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                                net.minecraft.world.level.ClipContext.Fluid.NONE,
                                player
                        ));
                        if (hr.getType() != HitResult.Type.BLOCK) continue;
                        if (hr instanceof BlockHitResult bhr) {
                            if (!bhr.getBlockPos().equals(p)) continue;
                        } else {
                            continue;
                        }

                        JsonObject jo = new JsonObject();
                        jo.addProperty("id", id);
                        jo.addProperty("distance", round1(dist));
                        jo.addProperty("x", p.getX());
                        jo.addProperty("y", p.getY());
                        jo.addProperty("z", p.getZ());
                        out.add(jo);
                        found++;
                    } catch (Throwable ignored) {}
                }
            }
        }
        return out;
    }

    private static boolean isInterestingBlockId(String id) {
        if (id == null || id.isBlank()) return false;
        // Strong POIs
        if (id.contains("chest") || id.contains("barrel") || id.contains("shulker_box")) return true;
        if (id.contains("spawner")) return true;
        if (id.contains("crafting_table") || id.contains("furnace") || id.contains("blast_furnace") || id.contains("smoker")) return true;
        if (id.contains("enchanting_table") || id.contains("anvil") || id.contains("brewing_stand") || id.contains("smithing_table")) return true;
        if (id.contains("lectern") || id.contains("loom") || id.contains("cartography_table") || id.contains("grindstone")) return true;
        if (id.contains("bed")) return true;
        if (id.contains("portal") || id.contains("end_portal") || id.contains("nether_portal")) return true;
        // Ores / valuables
        if (id.contains("_ore")) return true;
        if (id.contains("ancient_debris") || id.contains("amethyst") || id.contains("diamond") || id.contains("emerald")) return true;
        // Environmental danger cues
        if (id.contains("lava") || id.contains("campfire") || id.contains("magma")) return true;
        return false;
    }

    private static String safeName(Entity e) {
        try {
            String n = e.getName().getString();
            if (n != null && !n.isBlank()) return n;
        } catch (Throwable ignored) {}
        return "";
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
