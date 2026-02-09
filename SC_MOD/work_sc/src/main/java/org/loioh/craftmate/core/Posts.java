package org.loioh.craftmate.core;

import java.util.UUID;

import static org.loioh.craftmate.core.Core.EmptyMessage;
import static org.loioh.craftmate.core.Core.getEvents;

public class Posts {

    public static Object[] fixState(Object[] data,String message){
        UUID player = (UUID)data[0];
        return fixState(player,data,message);
    }

    public static Object[] fixState(UUID uuid,Object[] data,String message){
        data[2] = message;
        data[8] = getEvents(uuid);
        return data;
    }
    public static Object[] PostData(UUID player,Object[] data,String message) {
        data = fixState(player,data,message);
        Object[] answer = HTTP_Hook.postData(data);
        return answer;
    }
    public static Object[] PostData(Object[] data){
        data = fixState(data,EmptyMessage);
        Object[] answer = HTTP_Hook.postData(data);
        return answer;
    }
}
