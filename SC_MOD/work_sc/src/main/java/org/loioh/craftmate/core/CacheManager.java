package org.loioh.craftmate.core;

import org.checkerframework.checker.units.qual.A;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.loioh.craftmate.core.Core.maxEventsCacheAmount;

public class CacheManager {
    private static HashMap<UUID,List<String>> chatCache = new HashMap<>();
    private static HashMap<UUID,List<String>> eventCache = new HashMap<>();



    public static boolean clearChatCache(UUID player){
        if (chatCache.containsKey(player)) {
            chatCache.remove(player);
            return true;
        }
        return false;
    }
    public static boolean clearEventsCache(UUID player){
        if (eventCache.containsKey(player)) {
            eventCache.remove(player);
            return true;
        }
        return false;
    }



    public static void addToChatCache(UUID player,String a){
        List<String> messages = chatCache.getOrDefault(player, new ArrayList<>());
        if (!messages.contains(a)) {
            messages.add(a);
        }
        chatCache.put(player, messages);
    }
    public static void addToEventsCache(UUID player,String a){
        List<String> events = eventCache.getOrDefault(player, new ArrayList<>());
        if(events.size() >= maxEventsCacheAmount){
            events.remove(0);
        }

        events.add(a);
        eventCache.put(player, events);
    }



    public static List<String> getEventsCache(UUID player){
        return eventCache.getOrDefault(player,new ArrayList<>());
    }
}
