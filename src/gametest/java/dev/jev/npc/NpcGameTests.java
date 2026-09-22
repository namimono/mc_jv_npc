package dev.jev.npc;

import dev.jev.npc.behavior.ActionPlan;
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
}
