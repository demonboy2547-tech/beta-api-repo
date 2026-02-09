package org.loioh.craftmate;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.loioh.craftmate.core.Core;
import org.loioh.craftmate.entity.ModEntities;
import org.loioh.craftmate.utils.Audio;
import org.slf4j.Logger;//*;//

import static org.loioh.craftmate.utils.Schedule.runTask;
import static org.loioh.craftmate.utils.Audio.testMP3;


@Mod(CraftMate.MODID)
public class CraftMate {
    public static CraftMate inst;
    public static CraftMate getInstance(){
        return inst;
    }
    public static Core core = null;
    public static IEventBus modEventBus = null;
    public static final String MODID = "craftmate";

    public static final Logger LOGGER = LogUtils.getLogger();
    public static void log(String msg){
        if(Config.debugLog) {
            LOGGER.warn("[CraftMate]: " + msg);
        }
    }

// -------------------------
// Vision debug logging helpers (client-side)
// -------------------------
public static void vLog(String msg) {
    if (!Config.getVisionDebugEnabled()) return;
    try { LOGGER.info("[VISION] " + msg); } catch (Throwable ignored) {}
}

public static void vFatal(String msg, Throwable t) {
    try { LOGGER.error("[VISION] " + msg, t); } catch (Throwable ignored) {}
    try {
        org.loioh.craftmate.vision.VisionDebug.setLastError(msg + " (" + (t == null ? "" : t.getClass().getSimpleName()) + ")");
    } catch (Throwable ignored) {}
    if (!Config.getVisionDebugToast()) return;
    try {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[VISION ERROR] " + msg + " (" + (t == null ? "" : t.getClass().getSimpleName()) + ")"));
        }
    } catch (Throwable ignored) {}
}



    public CraftMate() {
        inst = this;
        //IEventBus
        modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.register(new BaseListener());
        MinecraftForge.EVENT_BUS.register(new EventsListListener());

        ModEntities.ENTITIES.register(modEventBus);

        Config._init();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);


    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        log("COMMON SETUP...");
    }


    public static Object getFromConfig(String key,Object def){
        return Config.getFromConfig(key,def);
    }

    // Convenience overload (keeps old call sites compiling)
    public static Object getFromConfig(String key){
        return getFromConfig(key, "");
    }


    public static void processAnswer(Player player, Object[] data) {
        if (player == null) return;
        if (data == null || data.length < 1) return;

        // Security / network errors from backend
        // Convention: HTTP_Hook returns {"__CM_ERROR__", title, detail, null}
        if (data[0] instanceof String && "__CM_ERROR__".equals(data[0])) {
            String title = (data.length > 1 && data[1] != null) ? String.valueOf(data[1]) : "Request failed";
            String detail = (data.length > 2 && data[2] != null) ? String.valueOf(data[2]) : "";
            String msg = detail.isEmpty() ? title : (title + ": " + detail);
            runTask(() -> {
                player.sendSystemMessage(Component.literal("[CraftMate] " + msg).withStyle(ChatFormatting.RED));
            });
            return;
        }

        // Normal AI reply
        if (data[0] instanceof String) {
            String msg = (String) data[0];
            Component message = parseColorCodes(CraftMate.core.chatPrefix + msg);
            runTask(() -> player.sendSystemMessage(message));
        }

        if (data.length > 3 && data[3] instanceof String) {
            Audio.playFromUrl((String) data[3]);
        }
    }
    public static Component parseColorCodes(String text) {
        text = text.replace('&', '§');
        return Component.literal(text);
    }

    public static void sendCraftMateError(String message) {
        try {
            // Client-side chat message
            if (Minecraft.getInstance() != null && Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.sendSystemMessage(
                        Component.literal("[CraftMate] " + message).withStyle(ChatFormatting.RED)
                );
            } else {
                log("CraftMateError: " + message);
            }
        } catch (Exception e) {
            log("CraftMateError failed: " + e.getMessage());
        }
    }

}