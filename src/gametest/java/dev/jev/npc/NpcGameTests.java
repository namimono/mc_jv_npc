package dev.jev.npc;

import dev.jev.npc.behavior.ActionPlan;
import dev.jev.npc.ai.AgentTask;
import dev.jev.npc.ai.GoalIntent;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class NpcGameTests implements FabricGameTest {
    private record Fixture(JevNpcEntity npc, ServerPlayer owner) {}

    private Fixture fixture(GameTestHelper helper) {
        JevNpcMod.config().enabled = false; // These tests must never contact a paid API.
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
    public void newOwnerRequestReplacesOldGoalAndBudgetTerminatesLoop(GameTestHelper helper) {
        var fixture = fixture(helper);
        var npc = fixture.npc();
        npc.brain().chat(fixture.owner(), "帮我收集木头");
        String firstId = npc.brain().taskState().get("id").getAsString();
        npc.skills().start(task(Skill.FOLLOW, null, 1), false);
        npc.brain().chat(fixture.owner(), "去水里");
        helper.assertFalse(npc.skills().hasTask(), "new owner goal must stop previous execution");
        helper.assertTrue(!firstId.equals(npc.brain().taskState().get("id").getAsString()), "new request must have a fresh identity");
        helper.assertTrue(npc.brain().taskState().get("request").getAsString().equals("去水里"), "new goal replaces old request");
        var exhausted = new AgentTask("去水里");
        exhausted.rounds = JevNpcMod.config().maxGoalRounds;
        var saved = new CompoundTag();
        saved.putString("agentTask", exhausted.save());
        saved.putString("ownerRequest", exhausted.request);
        npc.brain().load(saved);
        npc.brain().tick();
        helper.assertFalse(npc.brain().hasGoal(), "exhausted loop must end without another model call");
        helper.assertTrue(npc.brain().taskState().get("outcome").getAsString().equals("incomplete"), "budget stop must not claim success");
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
