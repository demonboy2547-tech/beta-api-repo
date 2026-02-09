package org.loioh.craftmate.core;


import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.loioh.craftmate.CraftMate;
import org.loioh.craftmate.utils.Audio;
import org.loioh.craftmate.utils.Players;

import java.util.*;

import static org.loioh.craftmate.CraftMate.getFromConfig;
import static org.loioh.craftmate.core.Posts.PostData;
import static org.loioh.craftmate.core.SimpleCounter.count;
import static org.loioh.craftmate.utils.Players.getStats;
import static org.loioh.craftmate.utils.Schedule.*;

import org.loioh.craftmate.scanner.SceneLoop;

public class Core {
    public static String EmptyMessage = "";
    public static Boolean disableOnServer = true;
    public static String link = "https://api.craftmate.workers.dev/craftmate/chat";
    public static String chatPrefix = "&3&l[AI]: &r";
    public static int postTicks = -1;
    public static Boolean postEvent = false;
    public static int maxEventsCacheAmount = 100;

    // Scene scanner
    public static boolean sceneScannerEnabled = true;
    public static int sceneIntervalTicks = 20;
    public static int sceneRadiusBlocks = 18;

    public static Boolean ClientMode = true;
    private static HTTP_Hook httpHook;


    public UUID corePlayerUUID = null;


    public Core(UUID corePlayerUUID) {
        link = (String) getFromConfig("linkAPI",link);
        postTicks = (int) getFromConfig("postIntervalTicks",postTicks);
        disableOnServer = (boolean) getFromConfig("disableOnServer",disableOnServer);
        chatPrefix = (String) getFromConfig("aiChatPrefix",chatPrefix);
        postEvent = (boolean) getFromConfig("postEveryNewEvent",postEvent);
        maxEventsCacheAmount = (int)getFromConfig("maxEventsCacheAmount",100);

        sceneScannerEnabled = (boolean) getFromConfig("sceneScannerEnabled", true);
        sceneIntervalTicks = (int) getFromConfig("sceneIntervalTicks", 20);
        sceneRadiusBlocks = (int) getFromConfig("sceneRadiusBlocks", 18);

        this.corePlayerUUID = corePlayerUUID;

        boolean sca = (boolean) getFromConfig("stopCurrentAudioIfReceiveNewOne",true);
        if(sca) {
            Audio.setMode(Audio.Mode.STOP_CURRENT);
        }else{
            Audio.setMode(Audio.Mode.WAIT_CURRENT);

        }

        httpHook = new HTTP_Hook();
    }

    public static void setClientMode(Boolean clientMode){
        //CraftMate.log("SET CLIENT MODE TO: " + clientMode);
        ClientMode = clientMode;
    }
    public static boolean getClientMode(){
        return ClientMode;
    }


    public void tickScheduler(){
        if(corePlayerUUID==null) return;

        // Realtime scene scanner (does not replace existing chat/events; it is an additive module)
        try {
            SceneLoop.tick(corePlayerUUID, sceneScannerEnabled, sceneIntervalTicks, sceneRadiusBlocks);
        } catch (Throwable ignored) {
            // never break gameplay loop
        }

        boolean reached = count("postTicksCircle",postTicks);
        if(reached){
            Object[] data = getStats(corePlayerUUID);
            runTaskAsync(()->{
                Object[] answer = postMessage(data);
                runTask(()-> {
                    CraftMate.processAnswer(Players.getPlayer(corePlayerUUID), answer);
                });
            });
        }
    }






    public Object[] postMessage(Object[] data){
        return postMessage(data,EmptyMessage);
    }
    public Object[] postMessage(Object[] data, String message){
        if(Core.disableOnServer && !Core.ClientMode) return null;
        UUID player = (UUID)data[0];

        if(message!=null && !message.equals(EmptyMessage)) {
            CacheManager.addToChatCache(player,message);
        }

        return PostData(player,data,message);
    }









    public void processEvent(Player player, Object[] data, String event) {
        runTaskAsync(() -> {
            Object[] answer = CraftMate.core.addEvent(data, event);
            if (answer != null) {
                Minecraft mc = Minecraft.getInstance();
                //CraftMate.log("Answer chat: " + answer);


                runTaskLater(() -> {
                    CraftMate.processAnswer(player, answer);
                }, 5); //Ticks
            }
        });
    }
    public Object[] addEvent(Object[] data, String event){
        if(Core.disableOnServer && !Core.ClientMode) return null;
        UUID player = (UUID)data[0];

        if(event!=null) {
            CacheManager.addToEventsCache(player,event);
        }

        if(postEvent){
            return PostData(data);
        }else{
            return null;
        }
    }


    public static String[] getEvents(UUID uuid){
        if(Core.disableOnServer && !Core.ClientMode) return null;

        List<String> events = CacheManager.getEventsCache(uuid);
        if(events==null || events.isEmpty()){
            return new String[0];
        }
        List<String> list = new ArrayList<>(events);
        Collections.reverse(list);
        return list.toArray(new String[0]);
    }





}
