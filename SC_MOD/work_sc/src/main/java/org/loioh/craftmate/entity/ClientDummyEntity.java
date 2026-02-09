package org.loioh.craftmate.entity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.loioh.craftmate.CraftMate;

import static org.loioh.craftmate.Config.*;
import static org.loioh.craftmate.CraftMate.parseColorCodes;
import static org.loioh.craftmate.core.Core.getClientMode;

public class ClientDummyEntity {
    private static Entity dummy;

    public static void ensureExists() {
        // Intentionally quiet: do not announce internal entity behavior in logs.
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;

        if (level == null || !level.isClientSide()) return;
        //CraftMate.log("DummyChecking...");

        if (dummy != null && dummy.level() == level && dummy.isAlive()) {
            //CraftMate.log("DummyFounded_0: " + dummy.toString());
            dummy.setPos(0.0, 0.0, 0.0);
            return;
        }

        Entity found = level.getEntity(entityID);

        if (found != null) {
            //CraftMate.log("DummyFounded_1: " + found.toString());
            found.discard();
        } else {
            //CraftMate.log("DummyFounded_1: null");
            remove();
        }


        //dummy = new ArmorStand(EntityType.ARMOR_STAND, level);
        dummy = new ModEntities.NullEntity(ModEntities.NULL_ENTITY.get(), level);
        if(entityUseTrueInvisible) {
            dummy.setInvisible(true);
        }else{
            MobEffectInstance effect = new MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false);
            ((ArmorStand)dummy).addEffect(effect);
        }

        // (no log)


        dummy.setId(entityID);

        //FIX!!!
        dummy.setCustomName(parseColorCodes(entityName));

        double [] arr = getDoubleArray(entityLocation);
        if(arr.length >= 3) {
            dummy.setPos(arr[0],arr[1],arr[2]);
        }else{
            dummy.setPos(0.5, 0.0, 0.5);
        }
        dummy.setNoGravity(true);
        dummy.setSilent(true);
        dummy.setInvulnerable(true);

        //CraftMate.log("DummyCreated: " + dummy.toString());


        //level.addFreshEntity(dummy);

        ServerLevel serverLevel = mc.getSingleplayerServer().getLevel(level.dimension());
        serverLevel.addFreshEntity(dummy);
        // (no log)

        /*
        GameRules.Key doMobSpawning = GameRules.RULE_DOMOBSPAWNING;
        GameRules.Value rule = level.getGameRules().getRule(doMobSpawning);
        if(  !((GameRules.BooleanValue)rule).get()  ) {
            CraftMate.log("DummySpawning_0...");
            rule.setFrom(GameRules.BooleanValue.create(true).createRule(), mc.getSingleplayerServer());
            level.addFreshEntity(dummy);
            rule.setFrom(GameRules.BooleanValue.create(false).createRule(), mc.getSingleplayerServer());
        }else {
         */
    }

    public static double[] getDoubleArray(String text){
        if(text == null) return null;
        text = text.replace(" ","");
        if(!text.contains(";")) return null;
        String[] p = text.split(";");
        double[] arr = new double[p.length];
        for(int i=0;i<p.length;i++){
            String p0 = p[i];
            try{
                arr[i] = Integer.parseInt(p0);
            } catch (NumberFormatException ex) {
                try{
                    arr[i] = Double.parseDouble(p0);
                } catch (NumberFormatException ex2) {
                    arr[i] = 0;
                }
            }
        }
        return arr;
    }

    public static void remove() {
        if (dummy != null) {
            dummy.discard();
            dummy = null;
        }
    }

    public static void onWorldLoad() {
        CraftMate.log("DummyStarting_0...");
        if(getClientMode()) {
            Minecraft.getInstance().tell(() -> ensureExists());
            //ensureExists();
        }
    }
}
