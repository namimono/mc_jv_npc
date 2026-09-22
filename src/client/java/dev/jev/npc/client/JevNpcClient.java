package dev.jev.npc.client;

import dev.jev.npc.JevNpcMod;
import dev.jev.npc.entity.JevNpcEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;

public final class JevNpcClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        EntityRendererRegistry.register(JevNpcMod.NPC, CompanionRenderer::new);
    }

    private static final class CompanionRenderer extends MobRenderer<JevNpcEntity, HumanoidModel<JevNpcEntity>> {
        private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");
        CompanionRenderer(EntityRendererProvider.Context context) {
            super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
            addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
            addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), context.getModelManager()));
        }
        @Override public ResourceLocation getTextureLocation(JevNpcEntity entity) { return TEXTURE; }
    }
}
