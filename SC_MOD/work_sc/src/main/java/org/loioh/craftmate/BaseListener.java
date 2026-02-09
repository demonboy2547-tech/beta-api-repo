package org.loioh.craftmate;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.loioh.craftmate.core.Core;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import org.loioh.craftmate.utils.Audio;
import org.loioh.craftmate.entity.ClientDummyEntity;
import org.loioh.craftmate.vision.VisionCapture;
import com.google.gson.JsonObject;

import java.util.UUID;

import static org.loioh.craftmate.CraftMate.*;
import static org.loioh.craftmate.core.Core.setClientMode;
import static org.loioh.craftmate.utils.Players.*;
import static org.loioh.craftmate.utils.Schedule.*;

public class BaseListener {

    // Dedupe: some environments can fire multiple hooks for the same chat send.
    private static long LAST_CHAT_VISION_AT = 0L;
    private static String LAST_CHAT_VISION_MSG = "";

    private static boolean shouldFireChatVision(String msg) {
        long now = System.currentTimeMillis();
        // If the exact same message is observed within a short window, assume duplicate hook.
        if (msg != null && msg.equals(LAST_CHAT_VISION_MSG) && (now - LAST_CHAT_VISION_AT) < 750L) {
            return false;
        }
        LAST_CHAT_VISION_AT = now;
        LAST_CHAT_VISION_MSG = (msg == null ? "" : msg);
        return true;
    }


    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null || mc.level == null) {
            if (CraftMate.core != null) {
                CraftMate.core = null;
            }
            return;
        }
        UUID uuid = mc.player.getUUID();

        if (CraftMate.core == null || !CraftMate.core.corePlayerUUID.equals(uuid)) {
            CraftMate.core = new Core(uuid);
            CraftMate.log("MateC initialized: " + mc.player.getName().getString()+ " ; "+ mc.player.getUUID());
        }

        if(CraftMate.core!=null) {
            CraftMate.core.tickScheduler();
        }
    }

    // Outgoing chat submit hook. Some environments may cancel/modify this event;
    // we still want to trigger a screenshot when the player *tries* to send a message.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onClientChat(ClientChatEvent event){

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        //CraftMate.log("Client chat: "+player.getName().getString()+" ; "+player.getUUID());
        UUID uuid = player.getUUID();

        // Chat-triggered vision: guarantee a screenshot when player sends a message.
        // Use reason="chat" so backend can treat this as user-intent.
        try {
            String msg = event.getMessage();
            if (msg != null && !msg.isBlank() && shouldFireChatVision(msg)) {
                JsonObject hint = new JsonObject();
                VisionCapture.forceCapture(uuid, hint, "chat", msg);
            }
        } catch (Throwable t) {
            CraftMate.LOGGER.warn("[VISION] chat hook error: {}", String.valueOf(t));
        }

        //CraftMate.log("Stats chat: "+getStats(uuid)+" ; "+event.getMessage());

        runTaskAsyncLater(() -> {
            Object[] answer = CraftMate.core.postMessage(getStats(uuid), event.getMessage());
            //CraftMate.log("Answer chat: "+answer);
            runTask(() -> {
                CraftMate.processAnswer(player, answer);
            }); //Ticks
        },5);
    }


    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {

        public static KeyMapping VISION_FORCE_KEY;

        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent e) {
            try {
                if (!Config.getVisionEnableF9Key()) return;
                VISION_FORCE_KEY = new KeyMapping("key.craftmate.vision_force",
                        InputConstants.Type.KEYSYM,
                        GLFW.GLFW_KEY_F9,
                        "key.categories.craftmate");
                e.register(VISION_FORCE_KEY);
            } catch (Throwable ignored) {}
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event){
            setClientMode(true);
            CraftMate.log("MINECRAFT USER_CLIENT NAME >> " + Minecraft.getInstance().getUser().getUuid());
        }
    }


    //SERVERS
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientConnectedToServer(ClientPlayerNetworkEvent.LoggingIn event) {
        if(event.isCanceled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCurrentServer() != null || (mc.level != null && !mc.isSingleplayer())) {
            //CraftMate.log("AI disable");
            setClientMode(false);
        }else{
            ClientDummyEntity.onWorldLoad();
        }
    }

    @SubscribeEvent
    public void onClientDisconnectedFromServer(ClientPlayerNetworkEvent.LoggingOut event) {
        //CraftMate.log("AI enable");
        Audio.onClientLogout();
        setClientMode(true);
    }



}
