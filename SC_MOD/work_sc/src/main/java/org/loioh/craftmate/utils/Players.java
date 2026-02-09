package org.loioh.craftmate.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.server.ServerLifecycleHooks;

import net.minecraftforge.eventbus.api.Event;

import net.minecraft.network.chat.Component.Serializer;
import org.loioh.craftmate.CraftMate;

import java.util.UUID;

public class Players {
    public static Player getPlayer(UUID uuid) {
        // CLIENT
        if (FMLEnvironment.dist.isClient()) {
            LocalPlayer local = Minecraft.getInstance().player;
            if (local != null && local.getUUID().equals(uuid)) {
                return local;
            }
        }
        // SERVER
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            return server.getPlayerList().getPlayer(uuid);
        }

        return null;
    }

    public static Object[] getStats(UUID player_uuid) {
        return getStats(getPlayer(player_uuid));
    }
    public static Object[] getStats(Player player) {
        if (player == null) return null;

        ResourceKey<Level> dimensionKey = player.level().dimension();
        String dimensionId = dimensionKey.location().toString();

        String biomeId = player.level()
                .getBiome(player.blockPosition())
                .unwrapKey()
                .map(key -> key.location().toString())
                .orElse(null);


        return new Object[]{
                player.getUUID(),
                player.getName().getString(),
                null,
                (int) player.getHealth(),
                player.getFoodData().getFoodLevel(),
                dimensionId,
                biomeId,
                player.level().isDay(),
                new String[0]
        };
    }


}
