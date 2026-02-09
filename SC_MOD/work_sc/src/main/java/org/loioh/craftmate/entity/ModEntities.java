package org.loioh.craftmate.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ArmorStandRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.loioh.craftmate.CraftMate;

import static org.loioh.craftmate.Config.entityName;


public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, CraftMate.MODID);

    public static final RegistryObject<EntityType<NullEntity>> NULL_ENTITY =
            ENTITIES.register("null", () ->
                    EntityType.Builder.of(NullEntity::new, MobCategory.MISC)
                            .sized(0.5f, 1.975f)
                            .clientTrackingRange(10)
                            .updateInterval(20)
                            .build("null")
            );


    @Mod.EventBusSubscriber(modid = CraftMate.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class EntityAttributeRegistry {
        @SubscribeEvent
        public static void registerAttributes(EntityAttributeCreationEvent event) {
            event.put(NULL_ENTITY.get(), NullEntity.createAttributes().build());
        }
    }


    @Mod.EventBusSubscriber(modid = CraftMate.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientRegistry {
        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            // Используем кастомный пустой renderer
            event.registerEntityRenderer(NULL_ENTITY.get(), InvisibleDummyRenderer::new);
        }
    }

    public static class InvisibleDummyRenderer extends EntityRenderer<NullEntity> {
        public InvisibleDummyRenderer(EntityRendererProvider.Context context) {
            super(context);
        }

        @Override
        public void render(NullEntity entity, float entityYaw, float partialTicks,
                           PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
            super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
        }

        @Override
        public ResourceLocation getTextureLocation(NullEntity entity) {
            return new ResourceLocation("minecraft", "textures/entity/armorstand/wood.png");
        }
    }

    public static class NullEntity extends ArmorStand {
        public NullEntity(EntityType<? extends ArmorStand> type, Level level) {
            super(type, level);
        }

        @Override
        public Component getName() {
            return Component.literal(entityName);
        }

        @Override
        protected Component getTypeName() {
            return Component.literal(entityName);
        }

        @Override
        public Component getDisplayName() {
            return Component.literal(entityName);
        }

        public static AttributeSupplier.Builder createAttributes() {
            return ArmorStand.createLivingAttributes();
        }
    }
}