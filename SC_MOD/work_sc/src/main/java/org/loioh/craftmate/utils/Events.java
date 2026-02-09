package org.loioh.craftmate.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.Event;
import org.loioh.craftmate.CraftMate;

import java.util.UUID;

import static org.loioh.craftmate.utils.Players.getStats;

public class Events {
    public static void preProcessEvent(String event){
        Player player = Minecraft.getInstance().player;
        if(player!=null){
            preProcessEvent(player,player.getUUID(),event);
        }
    }
    public static void preProcessEvent(String event,String data){
        Player player = Minecraft.getInstance().player;
        if(player!=null){
            preProcessEvent(player,player.getUUID(),event+":"+data);
        }
    }



    public static void preProcessEvent(Player player,UUID uuid, Event event){
        if(event.isCancelable()) return;
        String e = convertEventToString(event);

        preProcessEvent(player,uuid,e);
    }

    public static void preProcessEvent(Player player,UUID uuid, String e){
        boolean run = CraftMate.core!=null;
        //CraftMate.log("EVENT(): ["+e+"] ");// ("+run+")");
        if(run) {
            UUID orig = CraftMate.core.corePlayerUUID;
            if(orig.equals(uuid)){
                Object[] data = getStats(uuid);
                CraftMate.core.processEvent(player,data,e);
                //CraftMate.core.addEvent(data,e);
            }
        }
    }







    public static String convertEventToString(Event event){
        return event.getClass().getName();
    }
}
