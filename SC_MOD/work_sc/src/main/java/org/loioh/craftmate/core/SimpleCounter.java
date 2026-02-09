package org.loioh.craftmate.core;

import java.util.HashMap;

public class SimpleCounter {

    public static HashMap<String, Integer> data = new HashMap<>();
    public static Boolean count(String key,int max){
        if (max <= 0) return false;
        int i = 0;
        if(data.containsKey(key)) {
            i = data.get(key);
        }
        i+=1;
        if(i>=max) i=0;

        data.put(key,i);
        return (i==0)? true:false;
    }
}
