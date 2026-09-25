package dev.jev.npc;

import dev.jev.npc.ai.JevClient;
import dev.jev.npc.ai.RequestBudget;
import dev.jev.npc.command.NpcCommands;
import dev.jev.npc.config.ConfigStore;
import dev.jev.npc.config.NpcConfig;
import dev.jev.npc.entity.JevNpcEntity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Path;

public final class JevNpcMod implements ModInitializer {
    public static final String ID = "jev_npc";
    public static final Logger LOGGER = LoggerFactory.getLogger(ID);
    public static final EntityType<JevNpcEntity> NPC = Registry.register(BuiltInRegistries.ENTITY_TYPE,
        ResourceLocation.fromNamespaceAndPath(ID, "companion"),
        EntityType.Builder.of(JevNpcEntity::new, MobCategory.CREATURE).sized(0.6F, 1.8F)
            .clientTrackingRange(10).build(ID + ":companion"));
    private static NpcConfig config = new NpcConfig();
    private static JevClient client;
    private static final RequestBudget BUDGET = new RequestBudget();

    public static NpcConfig config() { return config; }
    public static JevClient client() { return client; }
    public static RequestBudget budget() { return BUDGET; }
    public static Path configPath() { return FabricLoader.getInstance().getConfigDir().resolve("jev-npc.json"); }

    @Override public void onInitialize() {
        FabricDefaultAttributeRegistry.register(NPC, JevNpcEntity.attributes());
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> NpcCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            client = new JevClient();
            BUDGET.clear();
            try { reload(server); }
            catch (IOException exception) {
                config = new NpcConfig();
                config.enabled = false;
                LOGGER.error("Jev configuration invalid; local skills remain available. Check {} and {}",
                    configPath(), ConfigStore.secretPath(configPath()));
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (client != null) client.close(); });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof JevNpcEntity npc) npc.brain().invalidate();
        });
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.signedContent();
            boolean directed = text.startsWith("@小杰") || text.startsWith("@jev");
            String request = directed ? text.substring(text.startsWith("@jev") ? 4 : 3).trim() : text.trim();
            if (request.isEmpty()) return;
            // Minecraft normally invokes chat on its server executor; explicitly schedule here too.
            sender.server.execute(() -> NpcCommands.nearest(sender).ifPresent(npc -> {
                if (directed || npc.brain().expectsReply(sender)) npc.brain().chat(sender, request);
            }));
        });
        LOGGER.info("Jev NPC Demo initialized for Fabric 1.21.1. Config: {}; secret: {}",
            configPath(), ConfigStore.secretPath(configPath()));
    }

    public static void reload(MinecraftServer server) throws IOException {
        NpcConfig replacement = ConfigStore.load(configPath());
        config = replacement;
        for (var level : server.getAllLevels()) for (var entity : level.getAllEntities()) {
            if (entity instanceof JevNpcEntity npc) npc.brain().resetAfterReload();
        }
        LOGGER.info("Jev configuration loaded; enabled={}, keyConfigured={}, model={}",
            config.enabled, !config.effectiveKey().isBlank(), config.model);
    }
}
