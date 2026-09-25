package dev.jev.npc.validation;

import dev.jev.npc.JevNpcMod;
import dev.jev.npc.ai.Communicator;
import dev.jev.npc.command.NpcCommands;
import dev.jev.npc.entity.JevNpcEntity;
import dev.jev.npc.navigation.Navigator;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Isolated real-client acceptance for the layered agent, driven by live Jev: a goal that needs building blocks the NPC
 * must dig first, a route through planks that needs the owner's permission (answered by typing in chat without @),
 * an idle initiative chosen from the NPC's own needs, and an unprompted nightfall remark.
 */
public final class ClientValidation implements ClientModInitializer {
    private static final String COME_HERE = "走到我现在站的位置";
    private final long started = System.nanoTime();
    private final StringBuilder evidence = new StringBuilder();
    private final List<String> received = new CopyOnWriteArrayList<>();
    private boolean opening;
    private volatile boolean finished, replied;
    private boolean goalStarted;
    private int stage, ticks, settled;
    private volatile int entityId = -1, capture, captured;
    private volatile String failure;
    private volatile String outboundChat;
    private int chatBaseline;
    private Communicator.Question pendingQuestion;
    private String pendingGoal;
    private JevNpcEntity npc;

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::clientTick);
        ServerTickEvents.END_SERVER_TICK.register(this::serverTick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> received.add(message.getString()));
    }

    private void clientTick(Minecraft client) {
        if (finished) return;
        try {
            if (failure != null) { finish(client, false, failure); return; }
            if ((System.nanoTime() - started) / 1_000_000_000 > 480) { finish(client, false, "Global timeout at stage " + stage); return; }
            client.options.pauseOnLostFocus = false;
            client.getTutorial().setStep(TutorialSteps.NONE);
            if (!opening && client.screen != null && client.getOverlay() == null && client.level == null) {
                opening = true;
                client.options.renderDistance().set(6);
                var rules = new GameRules();
                rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
                client.createWorldOpenFlows().createFreshLevel("layered-agent-" + System.currentTimeMillis(),
                    new LevelSettings("Jev layered agent validation", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(42042L, false, false),
                    registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), client.screen);
            }
            if (client.level == null || client.player == null) return;
            if (!(client.level.getEntity(entityId) instanceof JevNpcEntity)) return;
            if (outboundChat != null) {
                String message = outboundChat;
                outboundChat = null;
                client.player.connection.sendChat(message);
            }
            if (captured == 6 && !replied) {
                // The owner answers like a player would: plain chat, no @ prefix, through the real chat pipeline.
                client.player.connection.sendChat("可以，挖吧");
                replied = true;
            }
            if (capture == captured) return;
            client.options.hideGui = capture < 3;
            if (++settled < 20) return;
            if (capture == 3 && received.stream().noneMatch(text -> text.contains("可以挖穿吗"))) return;
            if (capture == 4 && received.stream().noneMatch(text -> text.contains("天快黑了"))) return;
            Screenshot.grab(client.gameDirectory, "stage-" + capture + ".png", client.getMainRenderTarget(), message -> {});
            captured = capture;
            settled = 0;
            if (capture == 4) finish(client, true, "Client received idle chat, two contextual explanations, the permission question and the nightfall remark; six rendered frames captured.");
        } catch (Throwable error) { finish(client, false, error.getClass().getSimpleName() + ": " + error.getMessage()); }
    }

    private void command(MinecraftServer server, ServerPlayer player, String command) {
        server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4), command);
    }
    private void record(String text) { evidence.append(text).append('\n'); JevNpcMod.LOGGER.info("CLIENT_VALIDATION {}", text); }
    private void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private void chat(String message) {
        chatBaseline = received.size();
        outboundChat = message;
    }
    private String latestReply() {
        return received.stream().skip(chatBaseline).filter(text -> text.startsWith("<小杰> ")).findFirst().orElse(null);
    }
    private void questionUnchanged(ServerLevel level) {
        require(npc.speech().pending().orElse(null) == pendingQuestion, "A clarification must keep the pending question");
        require(npc.brain().hasGoal() && npc.brain().taskState().get("id").getAsString().equals(pendingGoal), "A clarification must not replace the goal");
        require(npc.brain().grants().isEmpty(), "A clarification must not grant permission");
        require(level.getBlockState(new BlockPos(21, -60, 0)).is(Blocks.OAK_PLANKS), "Clarification must not break the wall");
    }
    /** With DeepSeek configured, a goal starts only after the conversation reply arrives. */
    private boolean completed() {
        if (!goalStarted) {
            goalStarted = npc.brain().hasGoal();
            return false;
        }
        if (npc.brain().hasGoal()) return false;
        var state = npc.brain().taskState();
        require(state.has("outcome") && state.get("outcome").getAsString().equals("completed"), "Goal ended incomplete: " + state);
        return true;
    }
    private static void fill(ServerLevel level, int x1, int y1, int z1, int x2, int y2, int z2, net.minecraft.world.level.block.Block block) {
        for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++)
            level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
    }
    private static void stand(ServerPlayer player, ServerLevel level, double x, double y, double z, float yaw, float pitch, boolean flying) {
        player.getAbilities().flying = flying;
        player.onUpdateAbilities();
        player.teleportTo(level, x, y, z, yaw, pitch);
    }

    private void serverTick(MinecraftServer server) {
        if (finished || failure != null) return;
        try {
            var players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) return;
            var player = players.getFirst();
            var level = player.serverLevel();
            if (++ticks > 2600) throw new IllegalStateException("Stage " + stage + " timed out: "
                + (npc == null ? "no NPC" : npc.brain().status() + "; " + npc.skills().summary() + "; " + npc.brain().taskState()));
            switch (stage) {
                case 0 -> {
                    if (ticks < 40) return;
                    require(!JevNpcMod.config().effectiveKey().isBlank(), "NO_KEY in isolated instance");
                    require(JevNpcMod.config().llmReady(), "DeepSeek must be enabled with a real key");
                    JevNpcMod.config().autonomyEnabled = false;
                    level.setDayTime(6000);
                    level.setWeatherParameters(0, 100000, false, false);
                    // Two floating islands 10 blocks up with a three-block gap; five dirt blocks on the NPC's island.
                    fill(level, -3, -51, 0, -1, -51, 4, Blocks.SMOOTH_STONE);
                    fill(level, -3, -50, 0, -3, -50, 4, Blocks.DIRT);
                    fill(level, 3, -51, 0, 6, -51, 4, Blocks.SMOOTH_STONE);
                    player.teleportTo(0.5, -60, 0.5);
                    command(server, player, "jev spawn diligent");
                    npc = NpcCommands.nearest(player).orElseThrow();
                    command(server, player, "jev stop");
                    npc.moveTo(-1.5, -50, 2.5, 0, 0);
                    entityId = npc.getId();
                    require(npc.skills().navigator().carriedBlocks() == 0, "The starter kit must hold no building blocks");
                    stand(player, level, 1.5, -46, 12.5, 180, 25, true);
                    capture = 1; stage = 1; ticks = 0;
                    record("Scene: NPC on a floating island without building blocks, owner island three blocks away, dirt on the NPC's island.");
                }
                case 1 -> {
                    if (captured != 1) return;
                    stand(player, level, 5.5, -50, 2.5, 90, 25, false);
                    chat("@小杰 你好，今天心情怎么样？");
                    stage = 11; ticks = 0;
                }
                case 11 -> {
                    require(!npc.brain().hasGoal(), "Idle chat must not create a goal");
                    String reply = latestReply();
                    if (reply == null) return;
                    require(!npc.skills().hasTask(), "Idle chat must not start an action");
                    record("DEEPSEEK CHAT: " + reply + "; no goal or action started.");
                    capture = 5; stage = 12; ticks = 0;
                }
                case 12 -> {
                    require(!npc.brain().hasGoal(), "Chat must remain task-free after rendering");
                    if (captured != 5) return;
                    stage = 2; ticks = 0;
                }
                case 2 -> {
                    if (ticks < 20) return;
                    command(server, player, "jev ask " + COME_HERE);
                    goalStarted = false;
                    stage = 3; ticks = 0;
                }
                case 3 -> {
                    if (!completed()) return;
                    require(npc.getX() > 3 && npc.getY() > -50.1, "NPC must cross to the owner's island: " + npc.position());
                    int bridge = 0, mound = 0;
                    for (int z = 0; z <= 4; z++) {
                        for (int x = 0; x <= 2; x++) if (level.getBlockState(new BlockPos(x, -51, z)).is(Blocks.DIRT)) bridge++;
                        if (level.getBlockState(new BlockPos(-3, -50, z)).is(Blocks.DIRT)) mound++;
                    }
                    require(bridge >= 3, "Three dirt blocks must bridge the gap, found " + bridge);
                    require(mound <= 2, "The bridge dirt must come from digging the island's own dirt, mound left " + mound);
                    record("BRIDGE: bridgeBlocks=" + bridge + " moundLeft=" + mound + " npc=" + npc.blockPosition().toShortString()
                        + " state=" + npc.brain().taskState());
                    stand(player, level, 1.5, -46, 12.5, 180, 25, true);
                    capture = 2; stage = 4; ticks = 0;
                }
                case 4 -> {
                    if (captured != 2) return;
                    // A sealed planks room: every way out means breaking blocks that may belong to a player.
                    fill(level, 19, -61, -1, 21, -58, 1, Blocks.OAK_PLANKS);
                    fill(level, 20, -60, 0, 20, -59, 0, Blocks.AIR);
                    npc.moveTo(20.5, -60, 0.5, 0, 0);
                    stand(player, level, 25.5, -60, 0.5, 90, 10, false);
                    stage = 5; ticks = 0;
                }
                case 5 -> {
                    if (ticks < 40) return;
                    command(server, player, "jev ask 从房子里出来，走到我这儿");
                    goalStarted = false;
                    stage = 6; ticks = 0;
                }
                case 6 -> {
                    var question = npc.speech().pending();
                    if (question.isEmpty()) return;
                    require(question.get().kind().equals("break_built"), "Expected a break_built question: " + question.get());
                    require(level.getBlockState(new BlockPos(21, -60, 0)).is(Blocks.OAK_PLANKS), "Nothing may be broken before the owner answers");
                    record("QUESTION: " + question.get().prompt());
                    pendingQuestion = question.get();
                    pendingGoal = npc.brain().taskState().get("id").getAsString();
                    goalStarted = true;
                    capture = 3; stage = 7; ticks = 0;
                }
                case 7 -> {
                    questionUnchanged(level);
                    if (captured != 3) return;
                    chat("为什么要挖墙？");
                    stage = 13; ticks = 0;
                }
                case 13 -> {
                    questionUnchanged(level);
                    String reply = latestReply();
                    if (reply == null) return;
                    require(List.of("墙", "木板", "房", "屋", "方块", "出口").stream().anyMatch(reply::contains),
                        "Explanation must refer to the pending route: " + reply);
                    record("QUESTION CHAT with Jev: " + reply + "; same goal and question, no grant.");
                    JevNpcMod.config().enabled = false;
                    chat("挖墙会弄坏房子吗？");
                    stage = 14; ticks = 0;
                }
                case 14 -> {
                    questionUnchanged(level);
                    String reply = latestReply();
                    if (reply == null) return;
                    record("QUESTION CHAT without Jev: " + reply + "; same goal and question, no grant.");
                    JevNpcMod.config().enabled = true;
                    capture = 6; stage = 15; ticks = 0;
                }
                case 15 -> {
                    if (!completed()) return;
                    require(npc.brain().taskState().get("owner_grants").toString().contains("break_built"), "The chat reply must grant permission");
                    int planks = 0;
                    for (int y = -61; y <= -58; y++) for (int x = 19; x <= 21; x++) for (int z = -1; z <= 1; z++)
                        if (level.getBlockState(new BlockPos(x, y, z)).is(Blocks.OAK_PLANKS)) planks++;
                    require(planks < 34, "The NPC must dig out once allowed; planks left " + planks);
                    require(npc.distanceTo(player) < 4, "NPC must reach the owner");
                    record("PERMISSION: planksLeft=" + planks + " state=" + npc.brain().taskState());
                    stage = 8; ticks = 0;
                }
                case 8 -> {
                    npc.home(npc.blockPosition());
                    npc.skills().equip(Items.IRON_CHESTPLATE, EquipmentSlot.CHEST);
                    npc.setHealth(npc.getMaxHealth());
                    for (int slot = 0; slot < npc.backpack().getContainerSize(); slot++)
                        if (Navigator.BUILDING_BLOCKS.contains(npc.backpack().getItem(slot).getItem())) npc.backpack().setItem(slot, ItemStack.EMPTY);
                    stand(player, level, 27.5, -57, 0.5, 90, 30, true);
                    JevNpcMod.config().autonomyEnabled = true;
                    record("AUTONOMY setup: idle diligent NPC near home with no building blocks; owner nearby; live Jev decides.");
                    stage = 9; ticks = 0;
                }
                case 9 -> {
                    if (npc.skills().navigator().carriedBlocks() < 4) return;
                    record("AUTONOMY: carried building blocks=" + npc.skills().navigator().carriedBlocks() + " decision=" + npc.brain().status()
                        + " activity=" + npc.skills().summary() + " afterTicks=" + ticks);
                    level.setDayTime(12100);
                    capture = 4; stage = 10; ticks = 0;
                }
                default -> {}
            }
        } catch (Throwable error) { failure = error.getClass().getSimpleName() + ": " + error.getMessage(); }
    }

    private void finish(Minecraft client, boolean passed, String detail) {
        if (finished) return;
        finished = true;
        try {
            Files.writeString(Path.of(client.gameDirectory.getAbsolutePath(), "result.txt"),
                (passed ? "PASS\n" : "FAIL\n") + evidence + "Chat received by client: " + received + "\n" + detail + "\n");
        } catch (Exception error) { JevNpcMod.LOGGER.error("Cannot write validation result", error); }
        client.stop();
    }
}
