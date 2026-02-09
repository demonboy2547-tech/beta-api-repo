package org.loioh.craftmate.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class Schedule {
    public static void runTask(Runnable task) {
        execute(task); // main thread MC
    }

    public static void runTaskAsync(Runnable task) {
        CompletableFuture.runAsync(task); // not block MC
    }

    //DELAYED
    public static void runTaskAsyncLater(Runnable task, int ticks) {
        CompletableFuture.delayedExecutor(ticks * 50, TimeUnit.MILLISECONDS)
                .execute(task);
    }

    public static void runTaskLater(Runnable task, int ticks) {
        CompletableFuture.delayedExecutor(ticks * 50, TimeUnit.MILLISECONDS)
                .execute(() -> execute(task));
    }

    private static void execute(Runnable task){
        // On a dedicated server, Minecraft.getInstance() is unavailable.
        // Route the task to the right main thread based on the current Dist.
        if (FMLEnvironment.dist.isClient()) {
            Minecraft.getInstance().execute(task);
            return;
        }

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.execute(task);
        } else {
            // Fallback: run immediately if we can't access a scheduler.
            task.run();
        }
    }
}
