package dev.jev.npc;

import dev.jev.npc.behavior.ActionPlan;
import dev.jev.npc.ai.AgentTask;
import dev.jev.npc.ai.GoalIntent;
import dev.jev.npc.ai.GoalPlan;
import dev.jev.npc.ai.EnvironmentTools;
import net.minecraft.world.item.ItemStack;
import dev.jev.npc.behavior.Skill;
import dev.jev.npc.entity.JevNpcEntity;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class NpcGameTests implements FabricGameTest {
    private record Fixture(JevNpcEntity npc, ServerPlayer owner) {}

    private Fixture fixture(GameTestHelper helper) {
        JevNpcMod.config().enabled = false; // These tests must never contact a paid API.
        JevNpcMod.config().llmEnabled = false;
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(x, 0, z, Blocks.DIRT);
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Vec3 position = helper.absoluteVec(new Vec3(1.5, 1, 1.5));
        owner.moveTo(position.x, position.y, position.z, 0, 0);
        JevNpcEntity npc = helper.spawn(JevNpcMod.NPC, new BlockPos(1, 1, 3));
        npc.initialize(owner, "diligent");
        npc.brain().hold();
        return new Fixture(npc, owner);
    }

    private static ActionPlan task(Skill skill, BlockPos position, int count) {
        return new ActionPlan("test_" + skill, skill, position, null, "", count, "Test " + skill);
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void traceSpeechUsesCurrentDialogueEvenAfterStop(GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        var npc = fixture.npc();
        npc.brain().startGoal(fixture.owner(), "old goal", new GoalIntent("mine", "stone", "owner", 12, true), false);
        var call = npc.brain().executionTrace().child("dialogue", new com.google.gson.JsonObject(), "dialogue", 987)
            .child("jev", dev.jev.npc.trace.TraceRecorder.data("purpose", "REVIEW"));
        try {
            var cause = npc.brain().getClass().getDeclaredField("actionCause");
            cause.setAccessible(true);
            Object previous = cause.get(npc.brain());
            cause.set(npc.brain(), call);
            try {
                npc.say("trace current dialogue reply");
                npc.brain().hold();
                npc.tellOwner("trace current dialogue stopped");
            } finally { cause.set(npc.brain(), previous); }
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        helper.succeedWhen(() -> {
            try {
                String snapshot = java.nio.file.Files.readString(JevNpcMod.trace().page().resolveSibling("events.jsonl"));
                var records = snapshot.substring(0, snapshot.lastIndexOf('\n') + 1).lines()
                    .map(line -> com.google.gson.JsonParser.parseString(line).getAsJsonObject())
                    .filter(row -> row.get("kind").getAsString().equals("speech") && row.get("phase").getAsString().equals("sent"))
                    .filter(row -> row.getAsJsonObject("data").get("text").getAsString().startsWith("trace current dialogue"))
                    .toList();
                helper.assertTrue(records.size() == 2, "both actual speech lines must be recorded");
                helper.assertTrue(records.stream().allMatch(row -> row.get("parent").getAsString().equals(call.id())
                    && row.getAsJsonObject("tags").get("dialogue").getAsInt() == 987), "reply and post-stop speech must retain current Jev decision and dialogue");
            } catch (java.io.IOException error) { helper.assertTrue(false, "wait for trace flush"); }
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void traceRecordsActualMethodResult(GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        var npc=fixture.npc();
        npc.setHealth(15);
        npc.skills().start(task(Skill.EAT, null, 1), false);
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getHealth() == 21, "actual method must heal");
            var recorder=JevNpcMod.trace();
            helper.assertTrue(recorder != null, "trace recorder must start with server");
            try {
                String snapshot=java.nio.file.Files.readString(recorder.page().resolveSibling("events.jsonl"));
                var rows=snapshot.substring(0, snapshot.lastIndexOf('\n') + 1).lines()
                    .map(line->com.google.gson.JsonParser.parseString(line).getAsJsonObject())
                    .filter(row->row.getAsJsonObject("tags").has("npc") && row.getAsJsonObject("tags").get("npc").getAsString().equals(npc.getUUID().toString()))
                    .filter(row->row.get("kind").getAsString().equals("method")).toList();
                var started=rows.stream().filter(row->row.get("phase").getAsString().equals("start")).findFirst();
                helper.assertTrue(started.isPresent(), "method start must be recorded");
                helper.assertTrue(rows.stream().anyMatch(row->row.get("span").equals(started.get().get("span"))
                    && row.get("phase").getAsString().equals("result") && row.getAsJsonObject("data").get("success").getAsBoolean()), "same invocation must record real success");
            } catch (java.io.IOException error) { helper.assertTrue(false, "trace must flush asynchronously"); }
        });
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void equipmentFoodAndPersistence(GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        var npc = fixture.npc();
        npc.skills().start(task(Skill.EQUIP, null, 1), false);
        helper.assertTrue(npc.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "must equip carried armor");
        helper.assertTrue(npc.backpack().countItem(Items.IRON_CHESTPLATE) == 0, "armor must leave backpack");
        npc.setHealth(10);
        npc.skills().start(task(Skill.EAT, null, 1), false);
        helper.assertTrue(npc.getHealth() == 16, "bread must heal 6");
        helper.assertTrue(npc.backpack().countItem(Items.BREAD) == 7, "bread must be consumed");
        npc.skills().start(task(Skill.BUILD, helper.absolutePos(new BlockPos(4, 1, 1)), 9), false);
        npc.skills().start(task(Skill.FLEE, helper.absolutePos(new BlockPos(2, 1, 3)), 1), true);
        CompoundTag saved = new CompoundTag();
        npc.addAdditionalSaveData(saved);
        JevNpcEntity restored = JevNpcMod.NPC.create(helper.getLevel());
        restored.readAdditionalSaveData(saved);
        helper.assertTrue(restored.isOwner(fixture.owner()), "owner must survive save/load");
        helper.assertTrue(restored.backpack().countItem(Items.OAK_PLANKS) == 32, "inventory must survive save/load");
        helper.assertTrue(restored.skills().hasSuspendedTask(), "suspended work must survive save/load");
        helper.assertTrue(restored.brain().status().startsWith("PAUSED"), "manual control mode must survive save/load");
        restored.skills().resume();
        helper.assertTrue(restored.skills().summary().startsWith("BUILD"), "must resume interrupted build");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 240)
    public void navigationActuallyMovesAndCompletes(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        BlockPos destination = helper.absolutePos(new BlockPos(6, 1, 3));
        npc.skills().start(task(Skill.MOVE, destination, 1), false);
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.distanceToSqr(Vec3.atBottomCenterOf(destination)) < 2.5, "must reach destination");
            helper.assertFalse(npc.skills().hasTask(), "move must report completion");
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 180)
    public void harvestingCollectsActualBlockDrops(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        helper.setBlock(3, 1, 3, Blocks.OAK_LOG);
        helper.setBlock(3, 2, 3, Blocks.OAK_LEAVES);
        npc.skills().start(task(Skill.HARVEST, helper.absolutePos(new BlockPos(3, 1, 3)), 1), false);
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.OAK_LOG, new BlockPos(3, 1, 3));
            helper.assertTrue(npc.backpack().countItem(Items.OAK_LOG) == 1, "one broken log must become one inventory log");
            helper.assertFalse(npc.skills().hasTask(), "harvest must finish");
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 180)
    public void miningUsesLootTableAndConsumesToolDurability(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        helper.setBlock(3, 1, 3, Blocks.STONE);
        npc.skills().start(task(Skill.MINE, helper.absolutePos(new BlockPos(3, 1, 3)), 1), false);
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(3, 1, 3));
            helper.assertTrue(npc.backpack().countItem(Items.COBBLESTONE) == 1, "stone should drop cobblestone");
            helper.assertTrue(npc.getMainHandItem().getDamageValue() == 1, "mining must wear the pickaxe");
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
    public void platformConsumesExactlyNinePlanks(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        npc.skills().start(task(Skill.BUILD, helper.absolutePos(new BlockPos(4, 1, 1)), 9), false);
        helper.succeedWhen(() -> {
            for (int x = 4; x < 7; x++) for (int z = 1; z < 4; z++) helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(x, 1, z));
            helper.assertTrue(npc.backpack().countItem(Items.OAK_PLANKS) == 23, "build must consume exactly nine planks");
            helper.assertFalse(npc.skills().hasTask(), "build must complete");
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 40)
    public void platformNeverOverwritesExistingBlocks(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        helper.setBlock(4, 1, 1, Blocks.GOLD_BLOCK);
        npc.skills().start(task(Skill.BUILD, helper.absolutePos(new BlockPos(4, 1, 1)), 9), false);
        helper.runAfterDelay(5, () -> {
            helper.assertBlockPresent(Blocks.GOLD_BLOCK, new BlockPos(4, 1, 1));
            helper.assertTrue(npc.backpack().countItem(Items.OAK_PLANKS) == 32, "failed build cannot consume materials");
            helper.assertFalse(npc.skills().hasTask(), "blocked build must fail promptly");
            helper.succeed();
        });
    }
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 240)
    public void blockedLogIsSkippedWithoutEndingHarvest(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        helper.setBlock(3, 1, 3, Blocks.OAK_LOG);
        helper.setBlock(3, 2, 3, Blocks.OAK_LEAVES);
        helper.setBlock(3, 0, 3, Blocks.AIR);
        for (int y = 0; y <= 3; y++) {
            helper.setBlock(2, y, 3, Blocks.STONE);
            helper.setBlock(4, y, 3, Blocks.STONE);
            helper.setBlock(3, y, 2, Blocks.STONE);
            helper.setBlock(3, y, 4, Blocks.STONE);
        }
        helper.setBlock(3, 3, 3, Blocks.STONE);
        helper.setBlock(1, 1, 6, Blocks.OAK_LOG);
        helper.setBlock(1, 2, 6, Blocks.OAK_LEAVES);
        npc.skills().start(task(Skill.HARVEST, helper.absolutePos(new BlockPos(3, 1, 3)), 1), false);
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(3, 1, 3));
            helper.assertBlockNotPresent(Blocks.OAK_LOG, new BlockPos(1, 1, 6));
            helper.assertTrue(npc.backpack().countItem(Items.OAK_LOG) == 1, "must skip the occluded log and collect the reachable log");
            helper.assertFalse(npc.skills().hasTask(), "harvest must finish after retry");
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 240)
    public void waterMovementRequiresActuallyEnteringWater(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        for (int x = 3; x <= 5; x++) for (int z = 2; z <= 4; z++) {
            helper.setBlock(x, -1, z, Blocks.DIRT);
            helper.setBlock(x, 0, z, Blocks.WATER);
        }
        BlockPos destination = helper.absolutePos(new BlockPos(4, 0, 3));
        npc.skills().start(new ActionPlan("water", Skill.MOVE, destination, null, "water", 1, "enter water"), false);
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.isInWater(), "must be in the water, not merely near the shore");
            helper.assertFalse(npc.skills().hasTask(), "water move must complete");
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void deliveryUsesPersistedTaskLedgerAndRetainsStarterKit(GameTestHelper helper) {
        var fixture = fixture(helper);
        var npc = fixture.npc();
        AgentTask goal = new AgentTask("帮我弄点木头");
        goal.intent = new GoalIntent("harvest", "log", "owner", 2, true);
        goal.gathered = 2;
        goal.collected.put("minecraft:oak_log", 2);
        CompoundTag brain = new CompoundTag();
        brain.putString("agentTask", goal.save());
        brain.putString("ownerRequest", goal.request);
        brain.putString("activeStep", "deliver_collected");
        npc.brain().load(brain);
        npc.backpack().addItem(new ItemStack(Items.OAK_LOG, 5));
        npc.skills().start(new ActionPlan("deliver_collected", Skill.GIVE, null, null, "", 1, "deliver task loot"), false);
        helper.succeedWhen(() -> {
            helper.assertTrue(fixture.owner().getInventory().countItem(Items.OAK_LOG) == 2, "only the task's two logs must be transferred");
            helper.assertTrue(npc.backpack().countItem(Items.OAK_LOG) == 3, "pre-existing logs must stay");
            helper.assertTrue(npc.backpack().countItem(Items.OAK_PLANKS) == 32, "starter planks must stay");
            helper.assertTrue(npc.backpack().countItem(Items.BREAD) == 8, "starter food must stay");
            helper.assertTrue(npc.brain().hasGoal(), "tool completion must retain the goal for the next decision");
            helper.assertTrue(npc.brain().taskState().get("completion_verified").getAsBoolean(), "delivery must satisfy completion conditions");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void observationsExcludeFailedTargetsAndNeverOfferPlayers(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        var environment = new EnvironmentTools(npc);
        var goal = new AgentTask("打那个生物");
        goal.intent = new GoalIntent("attack", "log", "owner", 1, false);
        environment.observe(goal, npc.blockPosition());
        for (var plan : environment.options(goal, npc.blockPosition()).values())
            helper.assertTrue(plan.target() == null || !plan.target().equals(npc.ownerId()), "owner/player must never be attack candidates");
        goal.intent = new GoalIntent("mine", "ground", "owner", 1, false);
        environment = new EnvironmentTools(npc);
        environment.observe(goal, npc.blockPosition());
        var plans = environment.options(goal, npc.blockPosition());
        var target = plans.values().stream().filter(plan -> plan.skill() == Skill.MINE).findFirst().orElseThrow();
        goal.failedTargets.add(target.id());
        helper.assertFalse(environment.options(goal, npc.blockPosition()).containsKey(target.id()), "failed target must not be offered again");
        helper.assertFalse(environment.options(goal, npc.blockPosition()).containsKey("finish_goal"), "search cannot complete goal");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 650)
    public void amendingUnfinishedMiningDeliversEightWithoutLosingTheFirstSeven(GameTestHelper helper) {
        var fixture = fixture(helper);
        var npc = fixture.npc();
        // Elevate this scene so observation cannot select natural underground stone or another test's blocks.
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(x, 20, z, Blocks.DIRT);
        for (int x = 3; x <= 6; x++) for (int y = 21; y <= 23; y++) helper.setBlock(x, y, 3, Blocks.STONE);
        Vec3 npcStart = helper.absoluteVec(new Vec3(1.5, 21, 3.5));
        npc.moveTo(npcStart.x, npcStart.y, npcStart.z, 0, 0);
        Vec3 ownerStart = helper.absoluteVec(new Vec3(1.5, 21, 1.5));
        fixture.owner().moveTo(ownerStart.x, ownerStart.y, ownerStart.z, 0, 0);
        AgentTask goal = new AgentTask("挖十二块交给我");
        goal.setPlan(new GoalPlan("十二块圆石", java.util.List.of(), "交付十二块", java.util.List.of(
            new GoalPlan.Stage("采集交付", new GoalIntent("mine", "stone", "owner", 12, true)))));
        CompoundTag state = new CompoundTag();
        state.putString("agentTask", goal.save());
        state.putString("ownerRequest", goal.request);
        npc.brain().load(state);
        runAcceptedTestStep(npc, new ActionPlan("mine_twelve", Skill.MINE, helper.absolutePos(new BlockPos(3, 21, 3)), null, "stone", 12, "mine twelve"));
        int[] phase = {0};
        helper.onEachTick(() -> {
            if (phase[0] == 0 && npc.backpack().countItem(Items.COBBLESTONE) == 7) {
                helper.assertTrue(npc.skills().hasTask(), "amend while the original mining step is still active");
                helper.assertTrue(npc.brain().taskState().get("gathered_blocks").getAsInt() == 0, "unfinished step has not reported completion");
                npc.brain().amendGoal("总共八块就够", new GoalPlan("八块圆石", java.util.List.of(), "交付八块", java.util.List.of(
                    new GoalPlan.Stage("采集交付", new GoalIntent("mine", "stone", "owner", 8, true), 0))));
                helper.assertTrue(npc.brain().taskState().get("id").getAsString().equals(goal.id), "amendment must retain goal identity");
                helper.assertTrue(npc.brain().taskState().get("gathered_blocks").getAsInt() == 7, "checkpoint all seven mined blocks exactly once");
                helper.assertFalse(npc.brain().options().containsKey("continue_current"), "a stopped step cannot be offered as running work");
                AgentTask amended = AgentTask.load(npc.brain().save().getString("agentTask"));
                var environment = new EnvironmentTools(npc);
                environment.observe(amended, npc.blockPosition());
                ActionPlan next = environment.options(amended, npc.blockPosition()).values().stream()
                    .filter(plan -> plan.skill() == Skill.MINE).findFirst().orElseThrow();
                helper.assertTrue(next.count() == 1, "the actual tool option must request just one more block");
                runAcceptedTestStep(npc, next);
                phase[0] = 1;
            } else if (phase[0] == 1 && !npc.skills().hasTask()) {
                helper.assertTrue(npc.backpack().countItem(Items.COBBLESTONE) == 8, "mine exactly one extra block");
                runAcceptedTestStep(npc, new ActionPlan("deliver_collected", Skill.GIVE, null, null, "", 1, "deliver amended total"));
                phase[0] = 2;
            } else if (phase[0] == 2 && !npc.skills().hasTask()) {
                helper.assertTrue(fixture.owner().getInventory().countItem(Items.COBBLESTONE) == 8, "deliver all eight, including the seven from before amendment; state=" + npc.brain().taskState()
                    + "; carried=" + npc.backpack().countItem(Items.COBBLESTONE) + "; owner=" + fixture.owner().getInventory().countItem(Items.COBBLESTONE));
                helper.assertTrue(npc.backpack().countItem(Items.COBBLESTONE) == 0, "no orphaned collected items remain");
                helper.assertTrue(npc.brain().taskState().get("completion_verified").getAsBoolean(), "amended total is verified complete");
                helper.assertTrue(npc.getMainHandItem().getDamageValue() == 8, "only eight blocks were actually mined");
                helper.succeed();
            }
        });
    }

    /** Offline harness chooses an actual grounded tool; production Jev uses the same saved active-step correlation. */
    private static void runAcceptedTestStep(JevNpcEntity npc, ActionPlan plan) {
        CompoundTag state = npc.brain().save();
        state.putString("activeStep", plan.id());
        npc.brain().load(state);
        npc.skills().start(plan, false);
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void lateAmendmentCapsDeliveryAndKeepsExcessInventory(GameTestHelper helper) {
        var fixture = fixture(helper);
        var npc = fixture.npc();
        AgentTask goal = new AgentTask("挖十二块交给我");
        goal.intent = new GoalIntent("mine", "stone", "owner", 12, true);
        goal.gathered = 9; goal.deliveredCount = 3;
        goal.collected.put("minecraft:cobblestone", 6);
        CompoundTag state = new CompoundTag(); state.putString("agentTask", goal.save());
        npc.brain().load(state);
        npc.backpack().addItem(new ItemStack(Items.COBBLESTONE, 6));
        fixture.owner().getInventory().add(new ItemStack(Items.COBBLESTONE, 3));
        npc.brain().amendGoal("总共八块就够", new GoalPlan("交付八块", java.util.List.of(), "交付八块", java.util.List.of(
            new GoalPlan.Stage("交付", new GoalIntent("mine", "stone", "owner", 8, true), 0))));
        runAcceptedTestStep(npc, new ActionPlan("deliver_collected", Skill.GIVE, null, null, "", 1, "deliver revised quota"));
        helper.succeedWhen(() -> {
            helper.assertFalse(npc.skills().hasTask(), "delivery must finish");
            helper.assertTrue(fixture.owner().getInventory().countItem(Items.COBBLESTONE) == 8, "prior three plus five new items equals the revised total");
            helper.assertTrue(npc.backpack().countItem(Items.COBBLESTONE) == 1, "excess is retained as inventory");
            helper.assertTrue(npc.brain().taskState().get("gathered_blocks").getAsInt() == 9, "actual over-collection must not be erased");
            helper.assertTrue(npc.brain().taskState().get("delivered_count").getAsInt() == 8, "delivery count persists separately from collection");
            helper.assertTrue(npc.brain().taskState().get("completion_verified").getAsBoolean(), "revised delivery is complete");
        });
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void dialoguePauseFreezesWorkPreservesProgressAndAllowsEmergencyEscape(GameTestHelper helper) {
        var fixture = fixture(helper);
        var npc = fixture.npc();
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(x, 20, z, Blocks.DIRT);
        Vec3 start = helper.absoluteVec(new Vec3(1.5, 21, 3.5));
        npc.moveTo(start.x, start.y, start.z, 0, 0);
        fixture.owner().moveTo(start.x, start.y, start.z - 2, 0, 0);
        helper.setBlock(3, 21, 3, Blocks.STONE); helper.setBlock(3, 22, 3, Blocks.STONE);
        ActionPlan plan = new ActionPlan("paused_mine", Skill.MINE, helper.absolutePos(new BlockPos(3, 21, 3)), null, "stone", 2, "mine two");
        npc.skills().start(plan, false);
        for (int i = 0; i < 42; i++) npc.skills().tick();
        helper.assertTrue(npc.skills().unfinishedGathered(plan.id()) == 1, "first block collected before pause");
        npc.skills().pauseForDialogue(true);
        for (int i = 0; i < 100; i++) npc.skills().tick();
        helper.assertTrue(npc.skills().unfinishedGathered(plan.id()) == 1 && npc.backpack().countItem(Items.COBBLESTONE) == 1,
            "paused work must keep progress without mining another block");
        npc.skills().pauseForDialogue(false);
        for (int i = 0; i < 60; i++) npc.skills().tick();
        helper.assertTrue(npc.backpack().countItem(Items.COBBLESTONE) == 2 && !npc.skills().hasTask(), "unpause completes remaining work");
        npc.skills().pauseForDialogue(true);
        npc.setRemainingFireTicks(100);
        npc.skills().tick();
        helper.assertTrue(npc.skills().emergencyLocked(), "a dialogue pause must not suppress immediate survival reflexes");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void permissionDeadlineStillExpiresWhileJevPausedWorkForDialogue(GameTestHelper helper) throws Exception {
        var npc = fixture(helper).npc();
        AgentTask goal = new AgentTask("到我这里");
        goal.intent = new GoalIntent("go_to", "log", "owner", 1, false);
        CompoundTag state = new CompoundTag(); state.putString("agentTask", goal.save()); npc.brain().load(state);
        // Seed a paid-model decision offline; exercise the real brain timer and permission boundary.
        var field = dev.jev.npc.ai.NpcBrain.class.getDeclaredField("dialogue"); field.setAccessible(true);
        var dialogue = (dev.jev.npc.ai.DialogueSession) field.get(npc.brain());
        dialogue.begin(dev.jev.npc.ai.DialogueSession.Mode.UNDERSTAND_PLAYER, "修改要求", "player", goal.id);
        dialogue.select("consult_and_pause", true, false);
        long deadline = helper.getLevel().getGameTime();
        var question = new dev.jev.npc.ai.Communicator.Question("break_built", "可以挖吗", java.util.Map.of("yes", "同意", "no", "拒绝"), "no", deadline);
        npc.speech().open(question);
        helper.assertTrue(npc.speech().canAnswer(question, deadline - 1), "same question is answerable before its deadline");
        helper.assertFalse(npc.speech().canAnswer(question, deadline), "even a not-yet-expired object cannot be answered at its deadline");
        npc.brain().tick();
        helper.assertTrue(npc.speech().pending().isEmpty(), "pause must not freeze question expiry");
        helper.assertTrue(npc.brain().grants().isEmpty(), "expired permission must never become a grant");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void amendmentRechecksPendingPermissionWithoutGrantingOrBlockingTheRevisedAction(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        AgentTask goal = new AgentTask("到我这里");
        goal.intent = new GoalIntent("go_to", "log", "owner", 1, false);
        CompoundTag state = new CompoundTag();
        state.putString("agentTask", goal.save());
        state.putString("activeStep", "go_to_destination");
        npc.brain().load(state);
        ActionPlan blocked = new ActionPlan("go_to_destination", Skill.MOVE, helper.absolutePos(new BlockPos(5, 1, 3)), null, "", 1, "move");
        npc.brain().stepFinished(blocked, false, 0, "需挖人造方块", "needs_permission:break_built");
        helper.assertTrue(npc.speech().pending().isPresent(), "executor failure creates a pending permission question");
        npc.brain().amendGoal("还是来这里，受伤先吃面包", new GoalPlan("到主人处", java.util.List.of("受伤先治疗"), "到达", java.util.List.of(
            new GoalPlan.Stage("到主人处", goal.intent, 0))));
        helper.assertTrue(npc.speech().pending().isEmpty(), "old action's question is invalidated");
        helper.assertTrue(npc.brain().grants().isEmpty(), "amendment is not a permission answer");
        helper.assertTrue(npc.brain().options().containsKey("go_to_destination"), "revised action must remain eligible to execute and ask afresh");
        helper.succeed();
    }
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void ownerCanTargetGolemButNeverPlayer(GameTestHelper helper) {
        var fixture = fixture(helper);
        var npc = fixture.npc();
        var golem = helper.spawn(net.minecraft.world.entity.EntityType.IRON_GOLEM, new BlockPos(3, 1, 3));
        golem.setNoAi(true);
        helper.assertTrue(npc.skills().nearestEnemy(10) != golem, "autonomous combat must not target golems");
        npc.skills().start(new ActionPlan("forbidden", Skill.ATTACK, null, fixture.owner().getUUID(), "owner_target", 1, "forbidden player"), false);
        npc.skills().tick();
        helper.assertFalse(npc.skills().hasTask(), "even explicit player attacks must be rejected");
        npc.skills().start(new ActionPlan("golem", Skill.ATTACK, null, golem.getUUID(), "owner_target", 1, "requested golem"), false);
        helper.succeedWhen(() -> helper.assertTrue(golem.getHealth() < golem.getMaxHealth(), "explicit non-hostile target must take damage"));
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void playerMessageQueuesWithoutReplacingOrStoppingPhysicalWork(GameTestHelper helper) {
        var fixture = fixture(helper);
        var npc = fixture.npc();
        npc.brain().startGoal(fixture.owner(), "跟随我", new GoalIntent("follow", "log", "owner", 1, false), false);
        String firstId = npc.brain().taskState().get("id").getAsString();
        npc.skills().start(task(Skill.FOLLOW, null, 1), false);
        npc.brain().chat(fixture.owner(), "为什么？");
        helper.assertTrue(npc.skills().hasTask(), "chat must not interrupt physical execution");
        helper.assertTrue(firstId.equals(npc.brain().taskState().get("id").getAsString()), "no proposal has been adopted yet");
        helper.assertTrue(npc.brain().dialogueState().get("phase").getAsString().equals("ROUTE"), "message first waits for Jev routing");
        npc.brain().tick();
        helper.assertTrue(npc.brain().dialogueState().get("llm_requests").getAsInt() == 0, "no DeepSeek fallback when Jev is off");
        npc.brain().hold();
        helper.succeed();
    }
    @GameTest(template = EMPTY_STRUCTURE)
    public void leafCoveredTrunkIsObservedAsWood(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        // Minimal spruce-like low canopy: all faces of the trunk are covered, no exposed air face.
        helper.setBlock(4, 1, 3, Blocks.SPRUCE_LOG);
        for (var direction : net.minecraft.core.Direction.values()) {
            if (direction == net.minecraft.core.Direction.DOWN) continue;
            helper.setBlock(new BlockPos(4, 1, 3).relative(direction), Blocks.SPRUCE_LEAVES);
        }
        var goal = new AgentTask("帮我弄点木头");
        goal.intent = new GoalIntent("harvest", "log", "owner", 1, true);
        var environment = new EnvironmentTools(npc);
        environment.observe(goal, npc.blockPosition());
        helper.assertTrue(environment.options(goal, npc.blockPosition()).values().stream()
            .anyMatch(plan -> plan.skill() == Skill.HARVEST), "A real trunk covered by leaves must not be reported as no wood");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
    public void knownGoalPausesWithoutJevInsteadOfFallingBackToLanguageOrLocalPolicy(GameTestHelper helper) {
        var fixture = fixture(helper);
        var npc = fixture.npc();
        helper.setBlock(3, 1, 3, Blocks.OAK_LOG);
        npc.brain().startGoal(fixture.owner(), "采集一块原木并交给我", new GoalIntent("harvest", "log", "owner", 1, true), false);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(npc.brain().hasGoal(), "goal is preserved while Jev is unavailable");
            helper.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(3, 1, 3));
            helper.assertTrue(npc.brain().dialogueState().get("llm_requests").getAsInt() == 0, "no language fallback");
            npc.brain().hold();
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 80)
    public void localAttackRefusesToGuessAmongNonPlayers(GameTestHelper helper) {
        var fixture = fixture(helper);
        var npc = fixture.npc();
        var villager = helper.spawn(net.minecraft.world.entity.EntityType.VILLAGER, new BlockPos(2, 1, 3));
        var golem = helper.spawn(net.minecraft.world.entity.EntityType.IRON_GOLEM, new BlockPos(4, 1, 3));
        villager.setNoAi(true);
        golem.setNoAi(true);
        npc.brain().startGoal(fixture.owner(), "打铁傀儡", new GoalIntent("attack", "log", "owner", 1, false), false);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(npc.brain().hasGoal(), "attack goal must pause without the Jev target selector");
            helper.assertTrue(villager.getHealth() == villager.getMaxHealth() && golem.getHealth() == golem.getMaxHealth(), "no nearby creature may be attacked");
            helper.assertTrue(npc.brain().status().contains("JEV_UNAVAILABLE"), "status must explain the paused decision");
            npc.brain().hold();
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 500)
    public void harvestingReachesTrunkBehindLeaves(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        helper.setBlock(4, 1, 3, Blocks.SPRUCE_LOG);
        for (var direction : net.minecraft.core.Direction.values()) {
            if (direction == net.minecraft.core.Direction.DOWN) continue;
            helper.setBlock(new BlockPos(4, 1, 3).relative(direction), Blocks.SPRUCE_LEAVES);
        }
        npc.skills().start(task(Skill.HARVEST, helper.absolutePos(new BlockPos(4, 1, 3)), 1), false);
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.backpack().countItem(Items.SPRUCE_LOG) == 1,
                "Leaves obstructing a trunk should be cleared locally, then the log collected");
            helper.assertFalse(npc.skills().hasTask(), "harvest should finish");
        });
    }
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void harvestingDoesNotClearPlayerPlacedLeaves(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        helper.setBlock(4, 1, 3, Blocks.SPRUCE_LOG);
        for (var direction : net.minecraft.core.Direction.values()) {
            if (direction == net.minecraft.core.Direction.DOWN) continue;
            helper.setBlock(new BlockPos(4, 1, 3).relative(direction),
                Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
        }
        npc.skills().start(task(Skill.HARVEST, helper.absolutePos(new BlockPos(4, 1, 3)), 1), false);
        helper.runAfterDelay(60, () -> {
            helper.assertBlockPresent(Blocks.SPRUCE_LOG, new BlockPos(4, 1, 3));
            for (var direction : net.minecraft.core.Direction.values()) {
                if (direction != net.minecraft.core.Direction.DOWN)
                    helper.assertBlockPresent(Blocks.SPRUCE_LEAVES, new BlockPos(4, 1, 3).relative(direction));
            }
            helper.assertFalse(npc.skills().hasTask(), "unclearable obstruction must terminate the local attempt");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void observationDistinguishesProtectedWoodFromNoWood(GameTestHelper helper) {
        var fixture = fixture(helper);
        var npc = fixture.npc();
        helper.setBlock(4, 1, 3, Blocks.SPRUCE_LOG);
        helper.setBlock(4, 2, 3, Blocks.SPRUCE_LEAVES);
        fixture.owner().getAbilities().mayBuild = false;
        var goal = new AgentTask("找木头");
        goal.intent = new GoalIntent("harvest", "log", "owner", 1, true);
        var environment = new EnvironmentTools(npc);
        environment.observe(goal, npc.blockPosition());
        helper.assertTrue(environment.state().get("detected_blocks").getAsInt() > 0, "physical wood detection must survive permission denial");
        helper.assertTrue(environment.options(goal, npc.blockPosition()).values().stream().noneMatch(plan -> plan.skill() == Skill.HARVEST),
            "protected blocks must not be offered for harvesting");
        helper.assertTrue(environment.unavailableMessage(false).contains("权限"), "must explain permission instead of saying no wood");
        fixture.owner().getAbilities().mayBuild = true;
        helper.succeed();
    }
    private static ActionPlan move(BlockPos destination) {
        return new ActionPlan("test_move", Skill.MOVE, destination, null, "", 1, "Test move");
    }

    /** Two stone islands four blocks above the floor, separated by {@code gap} open columns at x = 2 .. 1 + gap. */
    private static void islands(GameTestHelper helper, JevNpcEntity npc, int gap) {
        for (int z = 2; z <= 4; z++) {
            for (int x = 0; x <= 1; x++) helper.setBlock(x, 4, z, Blocks.STONE);
            for (int x = gap + 2; x <= gap + 3; x++) helper.setBlock(x, 4, z, Blocks.STONE);
        }
        Vec3 start = helper.absoluteVec(new Vec3(1.5, 5, 3.5));
        npc.moveTo(start.x, start.y, start.z, 0, 0);
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
    public void navigatorClimbsOntoRaisedPlatform(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        for (int x = 3; x <= 7; x++) for (int z = 1; z <= 5; z++) helper.setBlock(x, 1, z, Blocks.STONE);
        BlockPos destination = helper.absolutePos(new BlockPos(5, 2, 3));
        npc.skills().start(move(destination), false);
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.getY() >= destination.getY() - 0.01, "must climb onto the platform");
            helper.assertFalse(npc.skills().hasTask(), "move must complete");
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
    public void navigatorDigsOutOfSealedDirtRoom(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        for (int x = 0; x <= 2; x++) for (int y = 1; y <= 3; y++) for (int z = 2; z <= 4; z++)
            if (x != 1 || z != 3 || y == 3) helper.setBlock(x, y, z, Blocks.DIRT);
        BlockPos destination = helper.absolutePos(new BlockPos(5, 1, 3));
        npc.skills().start(move(destination), false);
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.distanceToSqr(Vec3.atBottomCenterOf(destination)) < 2.5, "must dig out and reach the destination");
            helper.assertFalse(npc.skills().hasTask(), "move must complete");
            helper.assertTrue(npc.backpack().countItem(Items.DIRT) > 0, "dug dirt must be kept as building blocks");
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void navigatorNeverDigsThroughPlanksWithoutPermission(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        for (int x = 0; x <= 2; x++) for (int y = 0; y <= 3; y++) for (int z = 2; z <= 4; z++)
            if (x != 1 || z != 3 || y == 0 || y == 3) helper.setBlock(x, y, z, Blocks.OAK_PLANKS);
        npc.skills().start(move(helper.absolutePos(new BlockPos(5, 1, 3))), false);
        helper.runAfterDelay(40, () -> {
            helper.assertFalse(npc.skills().hasTask(), "a route that needs breaking built blocks must end the move");
            helper.assertTrue(npc.skills().summary().contains("别人放置"), "failure must name the built blocks: " + npc.skills().summary());
            helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(2, 1, 3));
            helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(1, 0, 3));
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 600)
    public void permissionAnswerWaitsForJevAndPersistedGrantAllowsDigging(GameTestHelper helper) {
        var fixture = fixture(helper);
        var npc = fixture.npc();
        for (int x = 0; x <= 2; x++) for (int y = 0; y <= 3; y++) for (int z = 2; z <= 4; z++)
            if (x != 1 || z != 3 || y == 0 || y == 3) helper.setBlock(x, y, z, Blocks.OAK_PLANKS);
        AgentTask goal = new AgentTask("出来");
        goal.intent = new GoalIntent("go_to", "log", "owner", 1, false);
        CompoundTag brain = new CompoundTag();
        brain.putString("agentTask", goal.save());
        brain.putString("activeStep", "test_move");
        npc.brain().load(brain);
        BlockPos destination = helper.absolutePos(new BlockPos(5, 1, 3));
        npc.skills().start(move(destination), false);
        helper.runAfterDelay(30, () -> {
            helper.assertTrue(npc.speech().pending().map(q -> q.kind().equals("break_built")).orElse(false),
                "the NPC must ask before breaking built blocks");
            helper.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(2, 1, 3));
            npc.brain().chat(fixture.owner(), "可以，挖吧");
            helper.assertTrue(npc.speech().pending().isPresent(), "without Jev, chat alone cannot grant permission");
            helper.assertTrue(npc.brain().grants().isEmpty(), "no local keyword permission shortcut");
            // Exercise execution after a persisted grant; live routing/acceptance is covered by client validation.
            goal.grants.add("break_built");
            brain.putString("agentTask", goal.save());
            npc.brain().load(brain);
            npc.speech().resolve();
            npc.skills().start(move(destination), false);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.distanceToSqr(Vec3.atBottomCenterOf(destination)) < 2.5, "must dig out once allowed");
            helper.assertFalse(npc.skills().hasTask(), "move must complete");
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
    public void idleNpcPicksUpLooseDropsButNotItemsThePlayerThrew(GameTestHelper helper) {
        var fixture = fixture(helper);
        var npc = fixture.npc();
        Vec3 spot = helper.absoluteVec(new Vec3(2.5, 1, 4.5));
        var loose = new ItemEntity(helper.getLevel(), spot.x, spot.y, spot.z, new ItemStack(Items.FLINT, 3), 0, 0, 0);
        var thrown = new ItemEntity(helper.getLevel(), spot.x, spot.y, spot.z, new ItemStack(Items.EMERALD, 1), 0, 0, 0);
        loose.setNoPickUpDelay();
        thrown.setNoPickUpDelay();
        thrown.setThrower(fixture.owner());
        helper.getLevel().addFreshEntity(loose);
        helper.getLevel().addFreshEntity(thrown);
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.backpack().countItem(Items.FLINT) == 3, "loose drops within reach are collected");
            helper.assertTrue(thrown.isAlive() && npc.backpack().countItem(Items.EMERALD) == 0, "items a player threw are left alone");
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 800)
    public void idleNpcWithoutJevDoesNotInventAutonomousDecisions(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        for (int x = 4; x <= 6; x++) for (int z = 4; z <= 6; z++) helper.setBlock(x, 1, z, Blocks.DIRT);
        npc.brain().wake();
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(npc.backpack().countItem(Items.DIRT) == 0, "without Jev autonomy waits instead of using a fixed policy");
            helper.assertBlockPresent(Blocks.DIRT, new BlockPos(4, 1, 4));
            npc.brain().hold();
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
    public void navigatorJumpsSingleGap(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        islands(helper, npc, 1);
        npc.skills().start(move(helper.absolutePos(new BlockPos(4, 5, 3))), false);
        helper.succeedWhen(() -> {
            helper.assertFalse(npc.skills().hasTask(), "move must complete");
            helper.assertTrue(npc.getY() >= helper.absoluteVec(new Vec3(0, 5, 0)).y - 0.01, "must not fall into the gap");
            helper.assertTrue(npc.getX() > helper.absoluteVec(new Vec3(3, 0, 0)).x, "must land on the far island");
            helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(2, 4, 3));
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
    public void navigatorBridgesGapWithCarriedBlocks(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        islands(helper, npc, 3);
        npc.backpack().addItem(new ItemStack(Items.DIRT, 8));
        npc.skills().start(move(helper.absolutePos(new BlockPos(6, 5, 3))), false);
        helper.succeedWhen(() -> {
            helper.assertFalse(npc.skills().hasTask(), "move must complete");
            helper.assertTrue(npc.getX() > helper.absoluteVec(new Vec3(5, 0, 0)).x, "must cross to the far island");
            helper.assertTrue(npc.getY() >= helper.absoluteVec(new Vec3(0, 5, 0)).y - 0.01, "must not fall");
            helper.assertTrue(npc.backpack().countItem(Items.DIRT) == 5, "three blocks bridge the three-block gap");
            for (int x = 2; x <= 4; x++) helper.assertBlockPresent(Blocks.DIRT, new BlockPos(x, 4, 3));
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 60)
    public void navigatorReportsHowManyBlocksAreMissing(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        islands(helper, npc, 3);
        npc.skills().start(move(helper.absolutePos(new BlockPos(6, 5, 3))), false);
        helper.runAfterDelay(20, () -> {
            helper.assertFalse(npc.skills().hasTask(), "move must end when no route exists");
            helper.assertTrue(npc.skills().summary().contains("需要 3 块"), "must say how many blocks are missing: " + npc.skills().summary());
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 1200)
    public void moveDigsBuildingBlocksThenBridgesGap(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        for (int z = 1; z <= 5; z++) {
            for (int x = 0; x <= 2; x++) helper.setBlock(x, 4, z, Blocks.SMOOTH_STONE);
            for (int x = 6; x <= 7; x++) helper.setBlock(x, 4, z, Blocks.SMOOTH_STONE);
            helper.setBlock(0, 5, z, Blocks.DIRT);
        }
        Vec3 start = helper.absoluteVec(new Vec3(1.5, 5, 3.5));
        npc.moveTo(start.x, start.y, start.z, 0, 0);
        AgentTask goal = new AgentTask("去对面的平台");
        goal.intent = new GoalIntent("go_to", "log", "owner", 1, false);
        CompoundTag brain = new CompoundTag();
        brain.putString("agentTask", goal.save());
        brain.putString("activeStep", "test_move");
        npc.brain().load(brain);
        npc.skills().start(move(helper.absolutePos(new BlockPos(7, 5, 3))), false);
        helper.succeedWhen(() -> {
            helper.assertFalse(npc.skills().hasTask(), "move must resume after gathering and complete");
            helper.assertTrue(npc.getX() > helper.absoluteVec(new Vec3(6, 0, 0)).x, "must cross the gap");
            helper.assertTrue(npc.getY() >= start.y - 0.01, "must not fall");
            for (int x = 3; x <= 5; x++) helper.assertBlockPresent(Blocks.DIRT, new BlockPos(x, 4, 3));
            helper.assertTrue(npc.brain().collectedItems().isEmpty(), "dirt dug for bridging is not loot for the owner");
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
    public void navigatorWalksAroundLava(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        for (int z = 1; z <= 5; z++) helper.setBlock(4, 0, z, Blocks.LAVA);
        BlockPos destination = helper.absolutePos(new BlockPos(7, 1, 3));
        npc.skills().start(move(destination), false);
        helper.onEachTick(() -> { if (npc.isInLava() || npc.isOnFire()) helper.fail("must never touch lava"); });
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.distanceToSqr(Vec3.atBottomCenterOf(destination)) < 2.5, "must reach the far side");
            helper.assertFalse(npc.skills().hasTask(), "move must complete");
            for (int z = 1; z <= 5; z++) helper.assertBlockPresent(Blocks.LAVA, new BlockPos(4, 0, z));
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
    public void navigatorPillarsUpBesideTallColumn(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        for (int y = 1; y <= 3; y++) helper.setBlock(4, y, 3, Blocks.OAK_PLANKS);
        npc.backpack().addItem(new ItemStack(Items.DIRT, 6));
        BlockPos top = helper.absolutePos(new BlockPos(4, 4, 3));
        npc.skills().start(move(top), false);
        helper.succeedWhen(() -> {
            helper.assertFalse(npc.skills().hasTask(), "move must complete");
            helper.assertTrue(npc.getY() >= top.getY() - 1.01, "must pillar up next to the column");
            helper.assertTrue(npc.backpack().countItem(Items.DIRT) <= 4, "pillaring consumes carried dirt");
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 500)
    public void harvestTallTrunkFromGroundThroughLowCanopy(GameTestHelper helper) {
        var npc = fixture(helper).npc();
        for (int x = 3; x <= 5; x++) for (int z = 2; z <= 4; z++) for (int y = 1; y <= 5; y++)
            helper.setBlock(x, y, z, Blocks.SPRUCE_LEAVES);
        for (int y = 1; y <= 4; y++) helper.setBlock(4, y, 3, Blocks.SPRUCE_LOG);
        npc.skills().start(task(Skill.HARVEST, helper.absolutePos(new BlockPos(4, 1, 3)), 4), false);
        helper.succeedWhen(() -> {
            helper.assertTrue(npc.backpack().countItem(Items.SPRUCE_LOG) == 4,
                "All four reachable trunk blocks must be collected from ground-level working positions");
            helper.assertFalse(npc.skills().hasTask(), "tree harvest should finish");
        });
    }
}
