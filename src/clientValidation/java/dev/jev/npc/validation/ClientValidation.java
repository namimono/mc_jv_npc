package dev.jev.npc.validation;

import dev.jev.npc.JevNpcMod;
import dev.jev.npc.command.NpcCommands;
import dev.jev.npc.entity.JevNpcEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Isolated real-client acceptance: natural speech -> live Jev -> observations -> actions -> completion. */
public final class ClientValidation implements ClientModInitializer {
    private final long started = System.nanoTime();
    private final StringBuilder evidence = new StringBuilder();
    private boolean opening;
    private volatile boolean finished;
    private int stage, ticks, settled;
    private volatile int entityId = -1, capture, captured;
    private volatile String failure;
    private JevNpcEntity npc;
    private int initialDirt;

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::clientTick);
        ServerTickEvents.END_SERVER_TICK.register(this::serverTick);
    }

    private void clientTick(Minecraft client) {
        if (finished) return;
        try {
            if (failure != null) { finish(client, false, failure); return; }
            if ((System.nanoTime() - started) / 1_000_000_000 > 300) {
                finish(client, false, "Global timeout at stage " + stage); return;
            }
            client.options.pauseOnLostFocus = false;
            if (!opening && client.screen != null && client.getOverlay() == null && client.level == null) {
                opening = true;
                client.options.renderDistance().set(6);
                var rules = new GameRules();
                rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
                client.createWorldOpenFlows().createFreshLevel("agent-loop-" + System.currentTimeMillis(),
                    new LevelSettings("Jev agent loop validation", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(42042L, false, false),
                    registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), client.screen);
            }
            if (client.level == null || client.player == null || capture == captured) return;
            if (!(client.level.getEntity(entityId) instanceof JevNpcEntity visible)) return;
            client.options.hideGui = true;
            client.player.setYRot(capture >= 3 ? 120 : 175);
            client.player.setXRot(12);
            if (++settled < 20) return;
            if (capture == 2 && client.player.getInventory().countItem(Items.SPRUCE_LOG) != 4) return;
            if (capture == 3 && !visible.isInWater()) return;
            Screenshot.grab(client.gameDirectory, "stage-" + capture + ".png", client.getMainRenderTarget(), message -> {});
            captured = capture;
            settled = 0;
            if (capture == 4) finish(client, true, "Client replicated delivered logs and NPC in water; four rendered frames captured.");
        } catch (Throwable error) { finish(client, false, error.getClass().getSimpleName() + ": " + error.getMessage()); }
    }

    private void command(MinecraftServer server, ServerPlayer player, String command) {
        server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4), command);
    }
    private void ask(MinecraftServer server, ServerPlayer player, String text) {
        command(server, player, "jev ask " + text);
        ticks = 0;
    }
    private void record(String text) { evidence.append(text).append('\n'); JevNpcMod.LOGGER.info("CLIENT_VALIDATION {}", text); }
    private void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private boolean completed() {
        if (npc.brain().hasGoal()) return false;
        var state = npc.brain().taskState();
        require(state.has("outcome") && state.get("outcome").getAsString().equals("completed"), "Goal ended incomplete: " + state);
        return true;
    }

    private void serverTick(MinecraftServer server) {
        if (finished || failure != null) return;
        try {
            var players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) return;
            var player = players.getFirst();
            var level = player.serverLevel();
            if (++ticks > 2600) throw new IllegalStateException("Stage " + stage + " timed out: " + (npc == null ? "no NPC" : npc.brain().status() + "; " + npc.skills().summary()));
            switch (stage) {
                case 0 -> {
                    if (ticks < 40) return;
                    require(!JevNpcMod.config().effectiveKey().isBlank(), "NO_KEY in isolated instance");
                    level.setDayTime(6000);
                    level.setWeatherParameters(0, 100000, false, false);
                    player.teleportTo(0.5, -60, 0.5);
                    command(server, player, "jev spawn diligent");
                    npc = NpcCommands.nearest(player).orElseThrow();
                    command(server, player, "jev stop");
                    npc.moveTo(0.5, -60, 0.5, 0, 0);
                    entityId = npc.getId();
                    // Dense low spruce canopy: every trunk face is covered by leaves or another log.
                    for (int x = -1; x <= 1; x++) for (int z = 2; z <= 4; z++) for (int y = -60; y <= -56; y++)
                        level.setBlock(new BlockPos(x, y, z), Blocks.SPRUCE_LEAVES.defaultBlockState(), 3);
                    for (int y = -60; y <= -57; y++)
                        level.setBlock(new BlockPos(0, y, 3), Blocks.SPRUCE_LOG.defaultBlockState(), 3);
                    for (int x = 4; x <= 6; x++) for (int z = 1; z <= 3; z++)
                        level.setBlock(new BlockPos(x, -61, z), Blocks.WATER.defaultBlockState(), 3);
                    player.getAbilities().flying = true; player.onUpdateAbilities();
                    player.teleportTo(level, 1, -60, 9, 175, 12);
                    capture = 1; stage = 1; ticks = 0;
                    record("Fresh isolated world with four spruce logs entirely enclosed in a dense low canopy, plus shallow water; NPC rendered.");
                }
                case 1 -> {
                    if (captured != 1) return;
                    ask(server, player, "帮我搞点木头可以吗"); stage = 2;
                }
                case 2 -> {
                    if (!completed()) return;
                    require(player.getInventory().countItem(Items.SPRUCE_LOG) == 4, "Owner must receive exactly four logs");
                    require(npc.backpack().countItem(Items.SPRUCE_LOG) == 0, "Task logs must leave backpack");
                    require(npc.backpack().countItem(Items.OAK_PLANKS) == 32 && npc.backpack().countItem(Items.BREAD) == 8,
                        "Starter supplies must not be delivered");
                    var state = npc.brain().taskState();
                    require(state.get("round").getAsInt() >= 5 && state.toString().contains("observe_nearby")
                        && state.toString().contains("deliver_collected"), "Must use multiple real decisions and observation/delivery tools");
                    int leavesRemaining = 0;
                    for (int x = -1; x <= 1; x++) for (int z = 2; z <= 4; z++) for (int y = -60; y <= -56; y++)
                        if (level.getBlockState(new BlockPos(x, y, z)).is(Blocks.SPRUCE_LEAVES)) leavesRemaining++;
                    require(leavesRemaining < 41, "Harvest must clear obstructing leaves rather than mine through them");
                    record("WOOD leavesRemaining=" + leavesRemaining + "; " + state);
                    capture = 2; stage = 3; ticks = 0;
                }
                case 3 -> {
                    if (captured != 2) return;
                    player.teleportTo(level, 9, -60, 7, 120, 12);
                    ask(server, player, "去水里"); stage = 4;
                }
                case 4 -> {
                    if (!completed()) return;
                    // FloatGoal can briefly lift feet above shallow water. Wait for a wet sample, without moving the NPC.
                    if (!npc.isInWater()) return;
                    require(level.getFluidState(npc.blockPosition()).is(net.minecraft.tags.FluidTags.WATER)
                        || level.getFluidState(npc.blockPosition().below()).is(net.minecraft.tags.FluidTags.WATER),
                        "NPC must be over the water cell, not merely touching it from shore");
                    require(npc.brain().taskState().toString().contains("observe_nearby"), "Water must be discovered by tool");
                    record("WATER: " + npc.brain().taskState());
                    capture = 3; stage = 5; ticks = 0;
                }
                case 5 -> {
                    if (captured != 3) return;
                    initialDirt = npc.backpack().countItem(Items.DIRT) + player.getInventory().countItem(Items.DIRT);
                    // Replay the user's surrounding context: previous failed harvesting requests must not obscure a new dig command.
                    npc.personality("cautious");
                    npc.setHealth(29);
                    for (String memory : new String[]{"Owner said: 帮我搞点木头可以吗", "failed: HARVEST 目标被其他方块遮挡",
                        "Owner said: 攻击", "Owner said: 给我一些木头", "failed: HARVEST 区域内没有更多可触及的目标，已采集 1 个",
                        "failed: HARVEST 区域内没有更多可触及的目标，已采集 0 个", "Owner said: 挖地"}) npc.remember(memory);
                    npc.backpack().addItem(new net.minecraft.world.item.ItemStack(Items.SPRUCE_LOG, 4));
                    ask(server, player, "挖地面"); stage = 6;
                }
                case 6 -> {
                    if (!completed()) return;
                    require(npc.backpack().countItem(Items.DIRT) + player.getInventory().countItem(Items.DIRT) == initialDirt + 1,
                        "One surface dig must produce one dirt");
                    record("GROUND: " + npc.brain().taskState());
                    command(server, player, "jev stop");
                    capture = 4; stage = 7; ticks = 0;
                }
                default -> {}
            }
        } catch (Throwable error) { failure = error.getClass().getSimpleName() + ": " + error.getMessage(); }
    }

    private void finish(Minecraft client, boolean passed, String detail) {
        if (finished) return;
        finished = true;
        try {
            Files.writeString(Path.of(client.gameDirectory.getAbsolutePath(), "result.txt"), (passed ? "PASS\n" : "FAIL\n") + evidence + detail + "\n");
        } catch (Exception error) { JevNpcMod.LOGGER.error("Cannot write validation result", error); }
        client.stop();
    }
}
