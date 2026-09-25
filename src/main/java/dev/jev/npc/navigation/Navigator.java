package dev.jev.npc.navigation;

import dev.jev.npc.JevNpcMod;
import dev.jev.npc.entity.JevNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Set;

/**
 * Moves one NPC toward a goal. Skills call {@link #tick} every tick with the goal they want; planning runs in budgeted
 * slices, every step is re-checked against the live world, and deviations or stalls trigger a bounded number of replans.
 */
public final class Navigator {
    public enum Status { RUNNING, ARRIVED, FAILED }

    /** Cheap blocks the NPC may place to bridge or pillar. Planks are kept for building. */
    public static final Set<Item> BUILDING_BLOCKS = Set.of(Items.DIRT, Items.COARSE_DIRT, Items.COBBLESTONE,
        Items.COBBLED_DEEPSLATE, Items.STONE, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.NETHERRACK);
    private static final int RANGE = 64;
    private static final int MAX_REPLANS = 4;

    private final JevNpcEntity npc;
    private NavGoal goal;
    private NavPolicy policy;
    private PathPlanner planner;
    private List<PathStep> path;
    private int index, stepTicks, replans, digTicks;
    private boolean leapt;
    private BlockPos digging;
    private NavOutcome failure;

    public Navigator(JevNpcEntity npc) { this.npc = npc; }
    public NavOutcome failure() { return failure; }

    public void stop() {
        clearCracks();
        goal = null;
        policy = null;
        planner = null;
        path = null;
        failure = null;
        replans = 0;
        halt();
    }

    public Status tick(NavGoal wanted, NavPolicy wantedPolicy, double speed) {
        if (goal == null || !policy.equals(wantedPolicy) || !goal.sameAs(wanted)) {
            stop();
            policy = wantedPolicy;
        }
        goal = wanted;
        BlockPos feet = npc.blockPosition();
        boolean settled = npc.onGround() || npc.isInWater();
        if (settled && digging == null && goal.reached(feet)) {
            path = null;
            planner = null;
            replans = 0;
            if (goal.precise()) steer(Vec3.atBottomCenterOf(feet), Math.min(speed, 0.6));
            else halt();
            return Status.ARRIVED;
        }
        if (failure != null) {
            halt();
            return Status.FAILED;
        }
        if (path == null) return plan(feet, settled);
        if (index >= path.size()) {
            path = null;
            return Status.RUNNING;
        }
        execute(path.get(index), feet, speed);
        return failure == null ? Status.RUNNING : Status.FAILED;
    }

    private Status plan(BlockPos feet, boolean settled) {
        halt();
        if (planner == null) {
            if (!settled) return Status.RUNNING;
            planner = new PathPlanner(world(), feet, goal, policy, carriedBlocks(), JevNpcMod.config().navMaxNodes, RANGE);
        }
        var result = planner.step(JevNpcMod.config().navNodesPerTick);
        if (result.isEmpty()) return Status.RUNNING;
        planner = null;
        switch (result.get()) {
            case PathPlanner.Route route -> {
                path = route.steps();
                index = 0;
                stepTicks = 0;
                leapt = false;
                JevNpcMod.LOGGER.debug("Jev nav route npc={} steps={} partial={}", npc.getUUID(), path.size(), route.partial());
                return Status.RUNNING;
            }
            case PathPlanner.Failure rejected -> {
                failure = rejected.outcome();
                JevNpcMod.LOGGER.info("Jev nav failed npc={} from={} goal={} outcome={}", npc.getUUID(), feet.toShortString(), goal, failure.code());
                return Status.FAILED;
            }
        }
    }

    private void execute(PathStep step, BlockPos feet, double speed) {
        if (stepTicks++ == 0 && !stillValid(step)) { replan("changed"); return; }
        if (!feet.equals(step.from()) && !feet.equals(step.to()) && feet.distSqr(step.to()) > 5) { replan("deviated"); return; }
        if (stepTicks > 80 + step.breaks().size() * 120) { replan("stuck"); return; }
        Level level = npc.level();
        for (BlockPos target : step.breaks()) {
            if (!level.getBlockState(target).getCollisionShape(level, target).isEmpty()) {
                dig(target);
                return;
            }
        }
        clearCracks();
        if (step.place() != null && level.getBlockState(step.place()).canBeReplaced()) {
            if (step.move() == PathStep.Move.PILLAR) {
                pillar(step.place(), speed);
                return;
            }
            if (!place(step.place())) return;
        }
        if (step.move() == PathStep.Move.PARKOUR && !leapt) {
            Vec3 takeoff = Vec3.atBottomCenterOf(step.from());
            if (npc.onGround() && feet.equals(step.from()) && horizontal(takeoff) < 0.01) leap(step);
            else {
                steer(takeoff, 0.6);
                return;
            }
        }
        Vec3 target = Vec3.atBottomCenterOf(step.to());
        steer(target, speed);
        if (feet.equals(step.to()) && (npc.onGround() || npc.isInWater()) && horizontal(target) < 0.2) {
            index++;
            stepTicks = 0;
            replans = 0;
            leapt = false;
        }
    }

    private boolean stillValid(PathStep step) {
        NavWorld fresh = world();
        for (BlockPos pos : step.breaks()) {
            Cell cell = fresh.cell(pos);
            boolean breakable = policy.mayBreak() && cell.breakTicks() >= 0 && (cell.terrain() || policy.mayBreakBuilt());
            if (!cell.passable() && !breakable) return false;
        }
        for (BlockPos body : List.of(step.to(), step.to().above()))
            if (!fresh.cell(body).passable() && !step.breaks().contains(body)) return false;
        if (step.place() == null) return true;
        Cell place = fresh.cell(step.place());
        return place.placeable() || place.supports();
    }

    private void dig(BlockPos target) {
        Level level = npc.level();
        BlockState state = level.getBlockState(target);
        if (!mayModify(target) || state.getDestroySpeed(level, target) < 0 || level.getBlockEntity(target) != null) {
            replan("dig_blocked");
            return;
        }
        if (!target.equals(digging)) {
            clearCracks();
            digging = target.immutable();
            equipFor(state);
        }
        halt();
        Vec3 center = Vec3.atCenterOf(target);
        npc.getLookControl().setLookAt(center.x, center.y, center.z);
        ItemStack tool = npc.getMainHandItem();
        int needed = Math.max(1, breakTicks(level, state, target, tool));
        if (++digTicks % 5 == 1) npc.swing(InteractionHand.MAIN_HAND);
        level.destroyBlockProgress(npc.getId(), target, Math.min(9, digTicks * 10 / needed));
        if (digTicks < needed) return;
        List<ItemStack> drops = Block.getDrops(state, npc.serverLevel(), target, null, npc, tool);
        clearCracks();
        if (!level.destroyBlock(target, false, npc)) {
            replan("dig_failed");
            return;
        }
        for (ItemStack drop : drops) {
            ItemStack rest = npc.backpack().addItem(drop);
            if (!rest.isEmpty()) npc.spawnAtLocation(rest);
        }
        if (tool.isDamageableItem() && tool.getDestroySpeed(state) > 1) tool.hurtAndBreak(1, npc, EquipmentSlot.MAINHAND);
    }

    private void pillar(BlockPos spot, double speed) {
        Vec3 center = Vec3.atBottomCenterOf(spot);
        steer(new Vec3(center.x, npc.getY(), center.z), Math.min(speed, 0.5));
        if (npc.getBoundingBox().minY >= spot.getY() + 1 - 1e-3) place(spot);
        else if (npc.onGround() && horizontal(center) < 0.04) npc.getJumpControl().jump();
    }

    private boolean place(BlockPos pos) {
        Level level = npc.level();
        if (!mayModify(pos)) { replan("no_permission"); return false; }
        if (!level.getEntities((Entity) null, new AABB(pos), entity -> entity.isAlive() && entity.blocksBuilding).isEmpty()) return false;
        int slot = buildingBlockSlot();
        if (slot < 0) { replan("out_of_blocks"); return false; }
        BlockState state = ((BlockItem) npc.backpack().getItem(slot).getItem()).getBlock().defaultBlockState();
        Vec3 center = Vec3.atCenterOf(pos);
        npc.getLookControl().setLookAt(center.x, center.y, center.z);
        if (!level.setBlock(pos, state, Block.UPDATE_ALL)) { replan("place_failed"); return false; }
        npc.backpack().removeItem(slot, 1);
        npc.swing(InteractionHand.MAIN_HAND);
        SoundType sound = state.getSoundType();
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1) / 2, sound.getPitch() * 0.8F);
        return true;
    }

    private void leap(PathStep step) {
        leapt = true;
        double dx = Math.signum(step.to().getX() - step.from().getX()), dz = Math.signum(step.to().getZ() - step.from().getZ());
        npc.setDeltaMovement(dx * 0.42, 0.42, dz * 0.42);
        npc.hasImpulse = true;
    }

    private void replan(String reason) {
        clearCracks();
        path = null;
        planner = null;
        JevNpcMod.LOGGER.debug("Jev nav replan npc={} reason={} attempt={}", npc.getUUID(), reason, replans + 1);
        if (++replans > MAX_REPLANS) {
            failure = new NavOutcome.Unreachable(reason);
            JevNpcMod.LOGGER.info("Jev nav failed npc={} goal={} outcome={}", npc.getUUID(), goal, failure.code());
        }
    }

    public int carriedBlocks() {
        int count = 0;
        for (ItemStack stack : npc.backpack().getItems()) if (BUILDING_BLOCKS.contains(stack.getItem())) count += stack.getCount();
        return count;
    }

    private int buildingBlockSlot() {
        for (int slot = 0; slot < npc.backpack().getContainerSize(); slot++)
            if (BUILDING_BLOCKS.contains(npc.backpack().getItem(slot).getItem())) return slot;
        return -1;
    }

    private NavWorld world() {
        return new LevelNavWorld(npc.level(), this::mayModify, (state, pos) -> breakTicks(npc.level(), state, pos, bestTool(state)));
    }

    private boolean mayModify(BlockPos pos) { return npc.skills().mayModify(pos); }

    private ItemStack bestTool(BlockState state) {
        ItemStack best = npc.getMainHandItem();
        for (ItemStack stack : npc.backpack().getItems()) if (stack.getDestroySpeed(state) > best.getDestroySpeed(state)) best = stack;
        return best;
    }

    private void equipFor(BlockState state) {
        ItemStack best = bestTool(state);
        if (best != npc.getMainHandItem()) npc.skills().equip(best.getItem(), EquipmentSlot.MAINHAND);
    }

    /** Vanilla player mining time, without enchantments or effects. */
    static int breakTicks(Level level, BlockState state, BlockPos pos, ItemStack tool) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) return -1;
        if (hardness == 0) return 1;
        boolean harvest = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        return Math.min(200, (int) Math.ceil(hardness * (harvest ? 30 : 100) / Math.max(1, tool.getDestroySpeed(state))));
    }

    private void steer(Vec3 target, double speed) { npc.getMoveControl().setWantedPosition(target.x, target.y, target.z, speed); }
    private void halt() { npc.getMoveControl().setWantedPosition(npc.getX(), npc.getY(), npc.getZ(), 0); }

    private double horizontal(Vec3 target) {
        double dx = npc.getX() - target.x, dz = npc.getZ() - target.z;
        return dx * dx + dz * dz;
    }

    private void clearCracks() {
        if (digging != null) npc.level().destroyBlockProgress(npc.getId(), digging, -1);
        digging = null;
        digTicks = 0;
    }
}
