package org.loioh.craftmate.entity;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.loioh.craftmate.CraftMate;

import java.util.ArrayList;

import static org.loioh.craftmate.Config.entityName;


@Mod.EventBusSubscriber(modid = CraftMate.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class F3DebugOverride{

    private static final String ORIGINAL_ID = "craftmate:null";
    private static final String ORIGINAL_TRANSLATION = "entity.craftmate.null";

    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        Minecraft mc = Minecraft.getInstance();

        // Проверяем что целимся в нашу сущность
        Entity target = mc.crosshairPickEntity;
        if (!(target instanceof ModEntities.NullEntity)) {
            return;
        }

        // Получаем списки (это ССЫЛКИ на те же объекты что используются для рендера!)
        ArrayList<String> right = event.getRight();
        ArrayList<String> left = event.getLeft();

        // Модифицируем правый список (там инфа о сущности)
        boolean modified = modifyList(right);

        // На всякий случай проверяем левый
        modified |= modifyList(left);

        if (modified) {
            CraftMate.log("Debug text successfully modified!");
        }
    }

    private static boolean modifyList(ArrayList<String> list) {
        boolean changed = false;

        for (int i = 0; i < list.size(); i++) {
            String line = list.get(i);
            String newLine = line;

            // Заменяем ID сущности
            if (line.contains(ORIGINAL_ID)) {
                newLine = newLine.replace(ORIGINAL_ID, entityName);
                changed = true;
            }

            // Заменяем translation key
            if (line.contains(ORIGINAL_TRANSLATION)) {
                newLine = newLine.replace(ORIGINAL_TRANSLATION, entityName);
                changed = true;
            }

            // Если что-то изменилось - обновляем строку в списке
            if (!line.equals(newLine)) {
                list.set(i, newLine);
                CraftMate.log("Line " + i + " changed: '" + line + "' -> '" + newLine + "'");
            }
        }

        return changed;
    }
}