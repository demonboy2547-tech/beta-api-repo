package org.loioh.craftmate;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.loioh.craftmate.core.SimpleCounter;
import org.loioh.craftmate.entity.ClientDummyEntity;
import org.loioh.craftmate.scanner.ActivityTracker;
import org.loioh.craftmate.vision.VisionCapture;

import static org.loioh.craftmate.utils.Events.preProcessEvent;

public class EventsListListener {
    ///FIX!!! 4 events
    private boolean isClientPlayer(Player p) {
        LocalPlayer lp = Minecraft.getInstance().player;
        if (lp == null) return false;
        return lp.getUUID().equals(p.getUUID());
    }



    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Player p && isClientPlayer(p)) {
            preProcessEvent("onDeath");
        }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (isClientPlayer(event.getEntity())) {
            preProcessEvent("onRespawn");
        }
    }























    
    // -------------------------
    // Time/Weather
    // -------------------------
    public Boolean wasDay = null;

    private boolean lastIsRaining = false;
    private boolean lastIsThundering = false;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (!SimpleCounter.count("worldTicks",5)) return;

        if (!event.level.isClientSide()) return;
        //weather check change with new weather
        boolean isRain = event.level.isRaining();
        boolean isThunder = event.level.isThundering();

        if (isRain != lastIsRaining || isThunder != lastIsThundering) {
            String weather;
            if (!isRain) weather = "clear";
            else if (!isThunder) weather = "rain";
            else weather = "thunder";

            preProcessEvent("onWeatherChange",weather);

            lastIsRaining = isRain;
            lastIsThundering = isThunder;
        }

        //timeChange(day->night or night-day)

        long t = event.level.getDayTime() % 24000;
        boolean day = !(t >= 13000 && t <= 23000);

        if (wasDay!=null && day != wasDay) {
            String time = day ? "day" : "night";
            preProcessEvent("onTimeChange",time);
        }
        wasDay = day;
    }


    // -------------------------
    // GUI
    // -------------------------

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onInventoryOpen(ScreenEvent.Opening event) {
        boolean isPlayerInv =
                event.getScreen().getClass().getSimpleName().toLowerCase().contains("inventory");

        if (isPlayerInv) preProcessEvent("onInventoryOpen");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onInventoryClose(ScreenEvent.Closing event) {
        //check is player inventory or not
        boolean isPlayerInv =
                event.getScreen().getClass().getSimpleName().toLowerCase().contains("inventory");

        if (isPlayerInv) preProcessEvent("onInventoryClose");
    }



    // -------------------------
    // Interact
    // -------------------------
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockInteract(InputEvent.InteractionKeyMappingTriggered event) {
        InputConstants.Key key = event.getKeyMapping().getKey();
        //CraftMate.log("InteractKEY: "+key.getName()+" ; "+key.getValue());
        if (event.getKeyMapping().getKey().getValue() != 1) return;//1 - right click

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;
        HitResult hr = mc.hitResult;
        if (hr == null || hr.getType() != HitResult.Type.BLOCK) return;

        BlockHitResult bhr = (BlockHitResult) hr;
        BlockPos pos = bhr.getBlockPos();


        //BlockPos pos = mc.player.getOnPos();
        //Vec3 vector_3 = mc.hitResult.getLocation();
        //Vec3i vector_3i = new Vec3i((int)vector_3.x,(int)vector_3.y,(int)vector_3.z);
        //pos = pos.subtract(vector_3i);


        if (!(mc.level.getBlockState(pos).isAir())) {
            BlockState state = mc.level.getBlockState(pos);
            Block block = state.getBlock();

            String id = block.builtInRegistryHolder().key().location().toString();

            preProcessEvent("onBlockInteract",id);

            // Activity signals
            ActivityTracker.noteRightClickBlock(p);
        }
    }

    /**
     * Left click / attack key. We use this to approximate mining vs attacking vs miss swings.
     * This is client-side and does not wallhack: only uses the current crosshair hit result.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLeftClick(InputEvent.InteractionKeyMappingTriggered event) {
        // 0 - left click
        if (event.getKeyMapping().getKey().getValue() != 0) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;

        ActivityTracker.noteSwing(p);

        HitResult hr = mc.hitResult;
        if (hr == null) {
            ActivityTracker.noteSwingMiss(p);
            return;
        }

        if (hr.getType() == HitResult.Type.ENTITY) {
            ActivityTracker.noteAttackEntity(p);
            preProcessEvent("onAttack");
            return;
        }

        if (hr.getType() == HitResult.Type.BLOCK) {
            ActivityTracker.noteLeftClickBlock(p);
            preProcessEvent("onBlockHit");
            return;
        }

        // miss
        ActivityTracker.noteSwingMiss(p);
        preProcessEvent("onSwingMiss");
    }

    /**
     * Fallback chat send hook: if ClientChatEvent does not fire in some envs,
     * capture when the player presses ENTER in the chat screen.
     */
    @SubscribeEvent
    // Chat-trigger vision: fire when player presses ENTER in the chat UI.
    // (Forge exposes this as ScreenEvent.KeyPressed.*; KeyboardKeyPressed is not present in this target.)
    public void onChatScreenEnter(ScreenEvent.KeyPressed.Post event) {
        try {
            if (!(event.getScreen() instanceof net.minecraft.client.gui.screens.ChatScreen)) return;
            if (event.getKeyCode() != InputConstants.KEY_RETURN && event.getKeyCode() != InputConstants.KEY_NUMPADENTER) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            // Try to read the current chat text from ChatScreen's input box (reflect to avoid version lock).
            String msg = "";
            try {
                java.lang.reflect.Field f = event.getScreen().getClass().getDeclaredField("input");
                f.setAccessible(true);
                Object input = f.get(event.getScreen());
                if (input instanceof net.minecraft.client.gui.components.EditBox) {
                    msg = ((net.minecraft.client.gui.components.EditBox) input).getValue();
                }
            } catch (Throwable ignored) {
                // Best-effort only
            }

            // VisionCapture.forceCapture(UUID playerId, JsonObject extra, String reason, String source)
            JsonObject extra = new JsonObject();
            if (msg != null && !msg.isEmpty()) extra.addProperty("message", msg);
            VisionCapture.forceCapture(mc.player.getUUID(), extra, "chat", "player");
        } catch (Throwable t) {
            CraftMate.LOGGER.error("[VISION] chat screen hook error", t);
        }
    }







    // -------------------------
    // COMPLEX
    // -------------------------

    private double[] lastPos = new double[]{0,0,0};
    private float[] lastRot = new float[]{0f,0f};
    private String lastBiome = "";
    private String lastDimension = "";

    public float lastHealth = -1f;
    private int lastHunger = -1;
    private float lastSaturation = -1;
    private int lastHeld = -1;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBiomeChange(TickEvent.PlayerTickEvent event) {
        if (!(event.player instanceof LocalPlayer player)) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;



        // -------------------------
        //PLAYERS Location
        // -------------------------

        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();

        if ((int)x != (int)lastPos[0] || (int)y != (int)lastPos[1] || (int)z != (int)lastPos[2]) {
            preProcessEvent("onPlayerMove", x + "," + y + "," + z);
            lastPos[0] = x; lastPos[1] = y; lastPos[2] = z;
        }

        float pitch = player.getXRot();
        float yaw = player.getYRot();

        if (pitch != lastRot[0] || yaw != lastRot[1]) {
            preProcessEvent("onPlayerLook", pitch + "," + yaw);
            lastRot[0] = pitch; lastRot[1] = yaw;
        }


        // =======================================================
        // BIOME/DIMENSION CHANGE
        // =======================================================

        String biome = mc.level.getBiome(player.blockPosition())
                .unwrapKey().get().location().toString();
        if(lastBiome.equals("")) lastBiome = biome;
        if (!biome.equals(lastBiome)) {
            preProcessEvent("onBiomeChange", biome);
            lastBiome = biome;
        }

        String dimension = mc.level.dimension().location().toString();
        if(lastDimension.equals("")) lastDimension = dimension;
        if (!dimension.equals(lastDimension)) {
            preProcessEvent("onDimensionChange", dimension);
            ClientDummyEntity.onWorldLoad();
            lastDimension = dimension;
        }


        // =======================================================
        // Health + HUNGER + SATURATION
        // =======================================================

        float current = player.getHealth();
        if (lastHealth == -1f) lastHealth = current;
        if ((int)current != (int)lastHealth) {
            preProcessEvent("onHealthChange");
            if(current < lastHealth) {
                String reason = getDamageReason(player);
                preProcessEvent("onDamageTaken", reason);
                ActivityTracker.noteDamageTaken(player);
            }

            lastHealth = current;
        }



        int hunger = player.getFoodData().getFoodLevel();
        if(lastHunger == -1) lastHunger = hunger;
        if (hunger != lastHunger) {
            preProcessEvent("onHungerChange", String.valueOf(hunger));
            lastHunger = hunger;
        }
        float saturation = player.getFoodData().getSaturationLevel();

        if(lastSaturation == -1) lastSaturation = saturation;
        if ((int)saturation != (int)lastSaturation) {
            preProcessEvent("onSaturationChange", String.valueOf(saturation));
            lastSaturation = saturation;
        }

        // =======================================================
        // ITEM HELD CHANGE
        // =======================================================
        int held = player.getInventory().selected;
        if(lastHeld == -1) lastHeld = held;
        if (held != lastHeld) {
            preProcessEvent("onItemHeldChange", String.valueOf(held));
            lastHeld = held;
        }

        // =======================================================
        // NEAR MOB DETECTION
        // =======================================================

        if (SimpleCounter.count("mobDetectionTicks",20*5)) {
            final double RANGE = 12.0;
            for (Entity e : mc.level.getEntities(player, player.getBoundingBox().inflate(RANGE))) {
                if (e instanceof Mob mob) {
                    double dist = mob.distanceTo(player);
                    String id = mob.getType().builtInRegistryHolder().key().location().toString();

                    preProcessEvent("onNearMobDetected", id + ";" + dist);
                    //break;
                }
            }
        }
    }

    private String getDamageReason(Player p) {
        DamageSource src = p.getLastDamageSource();
        if (src == null) return "unknown";
        Entity direct = src.getDirectEntity();
        Entity cause = src.getEntity();

        if (cause instanceof Player) return "player";
        if (direct instanceof Player) return "player";
        if (cause instanceof Mob) return "mob";
        if (direct instanceof Mob) return "mob";

        if (src.is(DamageTypes.ON_FIRE) || src.is(DamageTypes.IN_FIRE)) return "fire";
        if (src.is(DamageTypes.IN_WALL)) return "wall";

        return src.type().msgId().toLowerCase();
        //return src.type().toString().toLowerCase();
    }
}