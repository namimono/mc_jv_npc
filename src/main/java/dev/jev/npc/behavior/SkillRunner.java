package dev.jev.npc.behavior;

import dev.jev.npc.JevNpcMod;
import dev.jev.npc.config.NpcConfig;
import dev.jev.npc.entity.JevNpcEntity;
import dev.jev.npc.navigation.NavGoal;
import dev.jev.npc.navigation.NavOutcome;
import dev.jev.npc.navigation.NavPolicy;
import dev.jev.npc.navigation.Navigator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.resources.ResourceLocation;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Owns work progress and interruption; all movement goes through the {@link Navigator}. It never calls a language model. */
public final class SkillRunner {
    private final JevNpcEntity npc;
    private final Navigator navigator;
    private Task active;
    private Task suspended;
    private String lastOutcome = "idle";
    private long emergencyUntil;
    private long lastSpeechTick = -200;

    private static final class Task {
        final ActionPlan plan;
        int progress;
        int ticks;
        int blockedTicks;
        int workTicks;
        int clearedLeaves;
        int leafTicks;
        int settleTicks;
        BlockPos clearingTarget;
        BlockPos approachTarget;
        BlockPos blockTarget;
        List<BlockPos> spots = List.of();
        final Set<BlockPos> skipped = new HashSet<>();
        /** Set on a recovery sub-task; the parent resumes when it ends. */
        Task parent;
        NavOutcome cause;
        int recoveries;
        Task(ActionPlan plan) { this.plan = plan; }
        boolean recovery() { return parent != null; }
        CompoundTag save() {
            CompoundTag tag = plan.save();
            tag.putInt("progress", progress);
            tag.putInt("clearedLeaves", clearedLeaves);
            tag.putLongArray("skipped", skipped.stream().mapToLong(BlockPos::asLong).toArray());
            return tag;
        }
        static Task load(CompoundTag tag) {
            Task task = new Task(ActionPlan.load(tag));
            task.progress = Math.clamp(tag.getInt("progress"), 0, 64);
            task.clearedLeaves = Math.clamp(tag.getInt("clearedLeaves"), 0, 24);
            for (long pos : tag.getLongArray("skipped")) task.skipped.add(BlockPos.of(pos));
            return task;
        }
    }

    public SkillRunner(JevNpcEntity npc) {
        this.npc = npc;
        this.navigator = new Navigator(npc);
    }
    public Navigator navigator() { return navigator; }
    public String summary() {
        if (active == null) return "idle (" + lastOutcome + ")";
        String progress = active.plan.skill() + " " + active.progress + "/" + active.plan.count();
        return active.recovery() ? "gathering building blocks " + progress + " for " + active.parent.plan.skill() : progress;
    }
    public boolean hasTask() { return active != null; }
    public boolean hasSuspendedTask() { return suspended != null; }
    public boolean emergencyLocked() { return npc.level().getGameTime() < emergencyUntil; }
    public String suspendedSummary() { return suspended == null ? "none" : suspended.plan.description(); }

    public void start(ActionPlan plan, boolean preserveCurrent) {
        if (plan.skill() == Skill.CONTINUE) return;
        if (plan.skill() == Skill.RESUME) { resume(); return; }
        if (active != null && active.plan.equals(plan)) return;
        if (plan.skill() == Skill.SPEAK) { speak(plan); return; }
        if (plan.skill() == Skill.EQUIP) {
            boolean result = equip(Items.IRON_CHESTPLATE, EquipmentSlot.CHEST);
            announceInstant(plan, result, result ? "装备了铁胸甲" : "背包里没有铁胸甲");
            return;
        }
        if (plan.skill() == Skill.EAT) {
            if (npc.backpack().countItem(Items.BREAD) > 0) {
                npc.backpack().removeItemType(Items.BREAD, 1);
                npc.heal(6);
                announceInstant(plan, true, "吃了面包，恢复了 6 点生命值（Demo 规则）");
            } else announceInstant(plan, false, "没有面包");
            return;
        }
        if (preserveCurrent && active != null && active.plan.skill() != Skill.FLEE && active.plan.skill() != Skill.ATTACK) {
            suspended = active;
        } else if (!preserveCurrent) suspended = null;
        clearCracks();
        navigator.stop();
        active = new Task(plan);
        lastOutcome = "running";
        npc.tellOwner(switch (plan.skill()) {
            case HARVEST -> "开始采集木头，目标 " + plan.count() + " 块。";
            case MINE -> "开始挖掘，目标 " + plan.count() + " 块。";
            case GIVE -> "我把这次采集的物品送给你。";
            case MOVE -> plan.argument().equals("water") ? "我去找到的浅水里。" : "我去指定位置。";
            case ATTACK -> "开始攻击选中的生物。";
            case BUILD -> "开始搭建 3×3 橡木平台。";
            case FOLLOW -> "我会跟着你。";
            case WAIT -> "我在这里等你。";
            case GUARD -> "我会守卫这里。";
            case FLEE -> "附近有危险，我先撤退。";
            default -> "开始执行。";
        });
    }

    private void announceInstant(ActionPlan plan, boolean success, String message) {
        npc.tellOwner(message);
        npc.remember(message);
        npc.brain().stepFinished(plan, success, 0, message);
        npc.brain().requestHandled();
    }

    public void stop() {
        clearCracks();
        active = null;
        suspended = null;
        emergencyUntil = 0;
        navigator.stop();
        lastOutcome = "cancelled by owner";
    }

    public void resume() {
        if (suspended == null) return;
        clearCracks();
        navigator.stop();
        active = suspended;
        suspended = null;
        active.blockedTicks = 0;
        active.workTicks = 0;
        npc.tellOwner("恢复任务：" + active.plan.description());
    }

    public void emergencyRetreat() {
        if (emergencyLocked()) return;
        emergencyUntil = npc.level().getGameTime() + 80;
        npc.brain().invalidate();
        Vec3 threat = npc.getLastHurtByMob() == null ? npc.position().add(0, 0, 2) : npc.getLastHurtByMob().position();
        Vec3 escape = DefaultRandomPos.getPosAway(npc, 12, 5, threat);
        BlockPos destination = escape == null ? npc.home() : BlockPos.containing(escape);
        start(new ActionPlan("emergency_flee", Skill.FLEE, destination, null, "", 1,
            "本地紧急撤退到附近安全位置"), true);
    }

    public void tick() {
        if ((npc.isOnFire() || npc.isInLava()) && !emergencyLocked()) emergencyRetreat();
        if (npc.tickCount % 10 == 0) pickUpNearbyItems();
        if (active == null) {
            lookAtOwner();
            return;
        }
        // Do not operate on a world for an absent owner, including after reload/dimension changes.
        ServerPlayer owner = npc.owner();
        if (owner == null || owner.level() != npc.level() || owner.distanceToSqr(npc) > 64 * 64) {
            navigator.stop();
            return;
        }
        active.ticks++;
        if (isWork(active.plan.skill()) && active.ticks > 2400) { finish(false, "任务超时"); return; }
        switch (active.plan.skill()) {
            case WAIT -> navigator.stop();
            case FOLLOW -> follow();
            case MOVE, FLEE -> move();
            case GUARD -> guard();
            case ATTACK -> attack();
            case HARVEST, MINE -> mine();
            case BUILD -> build();
            case GIVE -> give();
            default -> finish(false, "不支持的持续技能");
        }
    }

    private void lookAtOwner() {
        ServerPlayer owner = npc.owner();
        if (owner != null && owner.level() == npc.level() && npc.distanceToSqr(owner) <= 8 * 8) npc.getLookControl().setLookAt(owner, 30, 30);
    }

    /** Collects loose drops within reach, except items a player threw on purpose. */
    private void pickUpNearbyItems() {
        for (ItemEntity item : npc.level().getEntitiesOfClass(ItemEntity.class, npc.getBoundingBox().inflate(2, 1, 2),
                item -> item.isAlive() && !item.hasPickUpDelay() && !(item.getOwner() instanceof Player))) {
            ItemStack stack = item.getItem();
            ItemStack rest = npc.backpack().addItem(stack.copy());
            int taken = stack.getCount() - rest.getCount();
            if (taken == 0) continue;
            npc.take(item, taken);
            if (rest.isEmpty()) item.discard();
            else item.setItem(rest);
        }
    }

    private static boolean isWork(Skill skill) { return skill == Skill.HARVEST || skill == Skill.MINE || skill == Skill.BUILD || skill == Skill.MOVE || skill == Skill.GIVE; }

    private void follow() {
        ServerPlayer owner = npc.owner();
        if (npc.distanceToSqr(owner) <= 3 * 3) {
            navigator.stop();
            npc.getLookControl().setLookAt(owner, 30, 30);
        } else if (travel(NavGoal.near(owner.blockPosition(), 2), policy(), 1.05) == Navigator.Status.FAILED && !recover()) retryLater();
    }

    private void move() {
        BlockPos destination = active.plan.position();
        if (destination == null) { finish(false, "没有目的地"); return; }
        Vec3 target = Vec3.atBottomCenterOf(destination);
        boolean water = active.plan.argument().equals("water");
        if (water && !npc.level().getFluidState(destination).is(FluidTags.WATER)) {
            finish(false, "目标位置已经没有水"); return;
        }
        double horizontalDistance = Math.pow(npc.getX() - target.x, 2) + Math.pow(npc.getZ() - target.z, 2);
        boolean flee = active.plan.skill() == Skill.FLEE;
        boolean arrived = water ? horizontalDistance < 0.09 && npc.isInWater() : npc.onGround() && npc.distanceToSqr(target) < 2.5;
        if (arrived) {
            if (flee && emergencyLocked()) { navigator.stop(); return; }
            npc.setDeltaMovement(0, npc.getDeltaMovement().y, 0);
            finish(true, "已到达目的地");
            return;
        }
        NavGoal goal = water ? NavGoal.exact(destination) : NavGoal.near(destination, 1);
        if (travel(goal, flee ? walking() : policy(), flee ? 1.35 : 1.0) == Navigator.Status.FAILED) navigationFailed();
    }

    private void guard() {
        BlockPos anchor = active.plan.position();
        if (anchor == null) { finish(false, "缺少守卫位置"); return; }
        LivingEntity enemy = nearestEnemy(10);
        if (enemy != null && enemy.distanceToSqr(Vec3.atCenterOf(anchor)) <= 12 * 12) {
            combat(enemy);
        } else if (npc.distanceToSqr(Vec3.atBottomCenterOf(anchor)) > 2.5) {
            if (travel(NavGoal.near(anchor, 1), policy(), 1) == Navigator.Status.FAILED && !recover()) retryLater();
        } else navigator.stop();
    }

    private void attack() {
        Entity entity = active.plan.target() == null ? null : npc.serverLevel().getEntity(active.plan.target());
        if (!(entity instanceof LivingEntity target) || !target.isAlive()) { finish(true, "攻击目标已消失"); return; }
        if (target instanceof Player || target == npc || (!(target instanceof Enemy) && !active.plan.argument().equals("owner_target")) || target.distanceToSqr(npc) > 24 * 24) { finish(false, "目标不再是允许追击的敌人"); return; }
        combat(target);
    }

    private void combat(LivingEntity target) {
        equip(Items.IRON_SWORD, EquipmentSlot.MAINHAND);
        npc.getLookControl().setLookAt(target, 30, 30);
        if (npc.distanceToSqr(target) <= 2.5 * 2.5 && npc.hasLineOfSight(target)) {
            navigator.stop();
            if (active.ticks % 20 == 0) {
                npc.swing(InteractionHand.MAIN_HAND);
                npc.doHurtTarget(target);
            }
        } else if (travel(NavGoal.near(target.blockPosition(), 1), walking(), 1.15) == Navigator.Status.FAILED) {
            if (active.plan.skill() == Skill.ATTACK) finish(false, "够不到攻击目标", navigator.failure().code());
            else retryLater();
        }
    }

    public LivingEntity nearestEnemy(double radius) {
        return npc.level().getEntitiesOfClass(LivingEntity.class, npc.getBoundingBox().inflate(radius),
                entity -> entity instanceof Enemy && entity.isAlive() && npc.hasLineOfSight(entity))
            .stream().min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);
    }

    /** Search is bounded and only touches loaded chunks. Tree candidates require nearby leaves. */
    public Optional<BlockPos> findWorkBlock(BlockPos center, boolean logs) {
        return findWorkBlock(center, logs ? "log" : "stone", Set.of());
    }

    /** {@code material} is {@code log}, {@code ground}, {@code stone} or {@code blocks} (natural building-block sources). */
    public Optional<BlockPos> findWorkBlock(BlockPos center, String material) {
        return findWorkBlock(center, material, Set.of());
    }

    private Optional<BlockPos> findWorkBlock(BlockPos center, String material, Set<BlockPos> skipped) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos mutable : BlockPos.betweenClosed(center.offset(-8, -2, -8), center.offset(8, 6, 8))) {
            BlockPos position = mutable.immutable();
            if (skipped.contains(position) || npc.distanceToSqr(Vec3.atCenterOf(position)) > 20 * 20
                || !isWorkTarget(position, material) || material.equals("blocks") && standsOn(position)) continue;
            double distance = npc.distanceToSqr(Vec3.atCenterOf(position));
            if (distance < bestDistance) { bestDistance = distance; best = position; }
        }
        return Optional.ofNullable(best);
    }

    /** Blocks dug for bridging must not be anyone's floor or leave a hole that drops into open space. */
    private boolean standsOn(BlockPos position) {
        ServerPlayer owner = npc.owner();
        BlockPos below = position.below();
        return position.equals(npc.blockPosition().below()) || owner != null && position.equals(owner.blockPosition().below())
            || !npc.level().getBlockState(below).isFaceSturdy(npc.level(), below, Direction.UP);
    }

    public boolean isWorkTarget(BlockPos position, String material) {
        return observedWorkTarget(position, material) && mayModify(position);
    }

    /** Physical discovery is independent of line of sight, reachability and modification permission. */
    public boolean observedWorkTarget(BlockPos position, String material) {
        if (!npc.level().hasChunkAt(position)) return false;
        BlockState state = npc.level().getBlockState(position);
        return matchesMaterial(state, material)
            && (material.equals("log") ? hasLeavesNearby(position) : exposed(position));
    }

    private boolean matchesMaterial(BlockState state, String material) {
        return switch (material) {
            case "log" -> state.is(BlockTags.LOGS);
            case "ground" -> state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.ROOTED_DIRT);
            // Natural blocks whose drops are placeable building blocks.
            case "blocks" -> state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.ANDESITE)
                || state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE) || state.is(Blocks.TUFF) || state.is(Blocks.NETHERRACK);
            default -> state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE);
        };
    }

    private void skipWorkBlock() {
        clearCracks();
        if (active.blockTarget != null) {
            JevNpcMod.LOGGER.info("Jev work skip target={} npcPosition={} spots={}", active.blockTarget.toShortString(), npc.position(), active.spots.size());
            active.skipped.add(active.blockTarget);
        }
        active.blockTarget = null;
        active.spots = List.of();
        active.approachTarget = null;
        active.clearingTarget = null;
        active.leafTicks = 0;
        active.workTicks = 0;
        active.blockedTicks = 0;
        navigator.stop();
    }

    private boolean hasLeavesNearby(BlockPos position) {
        for (BlockPos nearby : BlockPos.betweenClosed(position.offset(-2, 0, -2), position.offset(2, 5, 2))) {
            if (npc.level().hasChunkAt(nearby) && npc.level().getBlockState(nearby).is(BlockTags.LEAVES)) return true;
        }
        return false;
    }

    private boolean exposed(BlockPos position) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = position.relative(direction);
            if (npc.level().hasChunkAt(neighbor) && npc.level().getBlockState(neighbor).isAir()) return true;
        }
        return false;
    }

    public boolean mayModify(BlockPos position) {
        ServerPlayer owner = npc.owner();
        return JevNpcMod.config().allowBlockChanges && npc.level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
            && owner != null && !owner.isSpectator() && owner.getAbilities().mayBuild
            && npc.level().hasChunkAt(position) && npc.level().getWorldBorder().isWithinBounds(position)
            && npc.level().mayInteract(owner, position) && npc.level().getBlockEntity(position) == null;
    }

    private void mine() {
        if (active.plan.position() == null) { finish(false, "没有作业区域"); return; }
        if (active.progress >= active.plan.count()) { finish(true, "采集数量已完成"); return; }
        boolean logs = active.plan.skill() == Skill.HARVEST;
        String material = logs ? "log" : switch (active.plan.argument()) {
            case "ground", "blocks" -> active.plan.argument();
            default -> "stone";
        };
        if (!equip(logs ? Items.IRON_AXE : Items.IRON_PICKAXE, EquipmentSlot.MAINHAND)) {
            finish(false, "缺少所需工具"); return;
        }
        if (active.blockTarget == null || npc.level().getBlockState(active.blockTarget).isAir()) {
            clearCracks();
            active.blockTarget = findWorkBlock(active.plan.position(), material, active.skipped).orElse(null);
            active.spots = List.of();
            active.approachTarget = null;
            active.clearingTarget = null;
            active.leafTicks = 0;
            active.workTicks = 0;
            if (active.blockTarget == null) { finish(false, "区域内没有更多可触及的目标，已采集 " + active.progress + " 个"); return; }
        }
        BlockPos target = active.blockTarget;
        if (!mayModify(target) || !matchesMaterial(npc.level().getBlockState(target), material)) { skipWorkBlock(); return; }
        Vec3 center = Vec3.atCenterOf(target);
        npc.getLookControl().setLookAt(center.x, center.y, center.z);
        var hit = traceBlock(npc.getEyePosition(), target);
        BlockPos obstruction = hit.getBlockPos();
        boolean occluded = !obstruction.equals(target);
        if (logs && occluded && canClearLeaf(obstruction, target)) {
            if (npc.getEyePosition().distanceToSqr(Vec3.atCenterOf(obstruction)) <= 3.5 * 3.5) {
                clearLeaf(obstruction);
            } else approachWorkBlock(obstruction);
            return;
        }
        if (npc.getEyePosition().distanceToSqr(center) > 3.5 * 3.5 || occluded) {
            approachWorkBlock(target);
            return;
        }
        if (active.clearingTarget != null) {
            clearCracks();
            active.clearingTarget = null;
            active.leafTicks = 0;
        }
        // Only the visible block is ever broken, even after repositioning.
        navigator.stop();
        active.blockedTicks = 0;
        BlockState state = npc.level().getBlockState(target);
        if (!matchesMaterial(state, material)) { skipWorkBlock(); return; }
        active.workTicks++;
        if (active.workTicks % 8 == 0) npc.swing(InteractionHand.MAIN_HAND);
        npc.level().destroyBlockProgress(npc.getId(), target, Math.min(9, active.workTicks / 4));
        if (active.workTicks < 40) return;
        ItemStack tool = npc.getMainHandItem();
        List<ItemStack> drops = Block.getDrops(state, npc.serverLevel(), target, null, npc, tool);
        clearCracks();
        if (npc.level().destroyBlock(target, false, npc)) {
            for (ItemStack drop : drops) {
                ItemStack copy = drop.copy();
                ItemStack remainder = npc.backpack().addItem(drop);
                copy.setCount(copy.getCount() - remainder.getCount());
                if (!active.recovery()) npc.brain().recordCollected(copy);
                if (!remainder.isEmpty()) npc.spawnAtLocation(remainder);
            }
            tool.hurtAndBreak(1, npc, EquipmentSlot.MAINHAND);
            active.progress++;
        } else { finish(false, "方块破坏失败"); return; }
        active.blockTarget = null;
        active.spots = List.of();
        active.approachTarget = null;
        active.clearingTarget = null;
        active.leafTicks = 0;
        active.workTicks = 0;
    }

    private void approachWorkBlock(BlockPos target) {
        if (!target.equals(active.approachTarget)) {
            navigator.stop();
            active.approachTarget = target;
            active.spots = workSpots(target);
            active.settleTicks = 0;
        }
        if (active.spots.isEmpty()) { skipWorkBlock(); return; }
        switch (travel(NavGoal.anyOf(active.spots), policy(), 1)) {
            // Standing on a working spot that still cannot see or reach the block: give up on this block.
            case ARRIVED -> { if (++active.settleTicks > 20) skipWorkBlock(); }
            case FAILED -> skipWorkBlock();
            case RUNNING -> {}
        }
    }

    private boolean canClearLeaf(BlockPos leaf, BlockPos log) {
        if (active.clearedLeaves >= 24 || leaf.distSqr(log) > 4 * 4 || !mayModify(leaf)) return false;
        BlockState state = npc.level().getBlockState(leaf);
        // Do not turn harvesting into arbitrary excavation or tear down player-placed leaf hedges.
        return state.is(BlockTags.LEAVES) && (!state.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
            || !state.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT));
    }

    private void clearLeaf(BlockPos leaf) {
        if (!leaf.equals(active.clearingTarget)) {
            clearCracks();
            active.clearingTarget = leaf.immutable();
            active.leafTicks = 0;
            active.workTicks = 0;
        }
        navigator.stop();
        active.blockedTicks = 0;
        Vec3 center = Vec3.atCenterOf(leaf);
        npc.getLookControl().setLookAt(center.x, center.y, center.z);
        if (++active.leafTicks % 4 == 0) npc.swing(InteractionHand.MAIN_HAND);
        npc.level().destroyBlockProgress(npc.getId(), leaf, Math.min(9, active.leafTicks));
        if (active.leafTicks < 10) return;
        // The caller rechecks permission, range and the actual ray obstruction on every tick.
        clearCracks();
        if (!npc.level().destroyBlock(leaf, true, npc)) { skipWorkBlock(); return; }
        active.clearedLeaves++;
        npc.getMainHandItem().hurtAndBreak(1, npc, EquipmentSlot.MAINHAND);
        JevNpcMod.LOGGER.info("Jev harvest cleared obstruction log={} leaf={} clearedLeaves={}",
            active.blockTarget.toShortString(), leaf.toShortString(), active.clearedLeaves);
        active.clearingTarget = null;
        active.leafTicks = 0;
        active.spots = List.of();
        active.approachTarget = null;
    }

    public String workVisibility(BlockPos target) {
        var hit = traceBlock(npc.getEyePosition(), target);
        if (hit.getBlockPos().equals(target)) return "visible; reach and path still require checking";
        return npc.level().getBlockState(hit.getBlockPos()).is(BlockTags.LEAVES)
            ? "occluded by leaves; harvesting can clear a bounded number of natural leaves"
            : "occluded by solid terrain; may require another approach";
    }

    private net.minecraft.world.phys.BlockHitResult traceBlock(Vec3 eye, BlockPos target) {
        return npc.level().clip(new net.minecraft.world.level.ClipContext(eye, Vec3.atCenterOf(target),
            net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, npc));
    }

    private boolean seesBlock(Vec3 eye, BlockPos target) {
        return traceBlock(eye, target).getBlockPos().equals(target);
    }

    private List<BlockPos> workSpots(BlockPos target) {
        List<BlockPos> spots = new ArrayList<>();
        for (BlockPos cursor : BlockPos.betweenClosed(target.offset(-3, -4, -3), target.offset(3, 1, 3))) {
            BlockPos pos = cursor.immutable();
            if (!npc.level().hasChunkAt(pos) || !npc.level().getWorldBorder().isWithinBounds(pos)
                || !npc.level().getBlockState(pos).isAir() || !npc.level().getBlockState(pos.above()).isAir()
                || !npc.level().getBlockState(pos.below()).isFaceSturdy(npc.level(), pos.below(), Direction.UP)) continue;
            Vec3 feet = Vec3.atBottomCenterOf(pos);
            Vec3 eye = feet.add(0, npc.getEyeHeight(), 0);
            if (eye.distanceToSqr(Vec3.atCenterOf(target)) <= 3.5 * 3.5 && seesBlock(eye, target)) spots.add(pos);
        }
        return spots;
    }

    private void give() {
        ServerPlayer owner = npc.owner();
        if (npc.distanceToSqr(owner) > 3 * 3) {
            if (travel(NavGoal.near(owner.blockPosition(), 2), policy(), 1) == Navigator.Status.FAILED) navigationFailed();
            return;
        }
        navigator.stop();
        boolean missing = false;
        for (var entry : npc.brain().collectedItems().entrySet()) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(entry.getKey()));
            int remaining = entry.getValue();
            for (int slot = 0; slot < npc.backpack().getContainerSize() && remaining > 0; slot++) {
                if (!npc.backpack().getItem(slot).is(item)) continue;
                ItemStack stack = npc.backpack().removeItem(slot, remaining);
                int moved = stack.getCount();
                // Overflow belongs to the owner and is dropped at their feet.
                owner.getInventory().add(stack);
                if (!stack.isEmpty()) owner.drop(stack, false);
                npc.brain().recordDelivered(entry.getKey(), moved);
                remaining -= moved;
                active.progress += moved;
            }
            if (remaining > 0) missing = true;
        }
        finish(!missing, missing ? "部分采集物已不在背包中，无法全部交付" : "已把本次采集的物品交给你");
    }

    private void build() {
        BlockPos origin = active.plan.position();
        if (origin == null) { finish(false, "没有建筑位置"); return; }
        if (active.progress >= 9) { finish(true, "3×3 橡木平台已完成"); return; }
        BlockPos target = origin.offset(active.progress % 3, 0, active.progress / 3);
        if (!mayModify(target)) { finish(false, "建筑位置不可修改"); return; }
        if (npc.level().getBlockState(target).is(Blocks.OAK_PLANKS)) { active.progress++; return; }
        if (!npc.level().getBlockState(target).isAir()) { finish(false, "建筑位置被占用，不会覆盖原有方块"); return; }
        if (npc.backpack().countItem(Items.OAK_PLANKS) == 0) { finish(false, "橡木木板不足"); return; }
        Vec3 center = Vec3.atCenterOf(target);
        if (npc.getEyePosition().distanceToSqr(center) > 4 * 4) {
            if (travel(NavGoal.near(origin.west(), 1), policy(), 1) == Navigator.Status.FAILED) navigationFailed();
            return;
        }
        navigator.stop();
        if (!npc.level().getEntities(npc, new AABB(target), Entity::isAlive).isEmpty()
            || npc.getBoundingBox().intersects(new AABB(target))) {
            active.blockedTicks++;
            if (active.blockedTicks > 100) finish(false, "有实体挡住建筑位置");
            return;
        }
        npc.getLookControl().setLookAt(center.x, center.y, center.z);
        if (++active.workTicks < 12) return;
        if (npc.level().setBlock(target, Blocks.OAK_PLANKS.defaultBlockState(), 3)) {
            npc.backpack().removeItemType(Items.OAK_PLANKS, 1);
            npc.swing(InteractionHand.MAIN_HAND);
            active.progress++;
            active.workTicks = 0;
        } else finish(false, "方块放置失败");
    }

    private Navigator.Status travel(NavGoal goal, NavPolicy policy, double speed) { return navigator.tick(goal, policy, speed); }

    private NavPolicy policy() {
        NpcConfig config = JevNpcMod.config();
        var grants = npc.brain().grants();
        return new NavPolicy(config.navAllowBreak, config.navAllowPlace, config.navMaxFall, grants.contains("risk"), grants.contains("break_built"));
    }

    private NavPolicy walking() { return NavPolicy.walking(JevNpcMod.config().navMaxFall); }

    /** Continuous skills survive a failed route and try again every few seconds. */
    private void retryLater() { if (active.ticks % 100 == 0) navigator.stop(); }

    private void navigationFailed() {
        NavOutcome failure = navigator.failure();
        if (!recover()) finish(false, describe(failure), failure == null ? "unreachable" : failure.code());
    }

    /** Starts a recovery sub-task for a failed route when a rule covers it; the current task resumes afterwards. */
    private boolean recover() {
        NavOutcome failure = navigator.failure();
        if (failure == null || active.recovery() || active.recoveries >= Recovery.MAX_ATTEMPTS) return false;
        NpcConfig config = JevNpcMod.config();
        var fix = Recovery.plan(failure, npc.blockPosition(), config.allowBlockChanges && config.navAllowBreak);
        if (fix.isEmpty()) return false;
        Task child = new Task(fix.get());
        child.parent = active;
        child.cause = failure;
        active.recoveries++;
        navigator.stop();
        active = child;
        JevNpcMod.LOGGER.info("Jev recovery npc={} for={} outcome={} plan={}x{}", npc.getUUID(), child.parent.plan.id(),
            failure.code(), fix.get().argument(), fix.get().count());
        npc.tellOwner(describe(failure) + "，我先在附近挖 " + fix.get().count() + " 块泥土或石头垫脚。");
        return true;
    }

    private void endRecovery(boolean enough, String reason) {
        Task child = active;
        clearCracks();
        navigator.stop();
        active = child.parent;
        JevNpcMod.LOGGER.info("Jev recovery ended npc={} for={} gathered={} detail={}", npc.getUUID(), active.plan.id(), child.progress, reason);
        if (enough) npc.tellOwner("垫脚方块准备好了，继续出发。");
        else finish(false, describe(child.cause) + "，附近也挖不到可用的泥土或石头", child.cause.code());
    }

    public static String describe(NavOutcome outcome) {
        return switch (outcome) {
            case NavOutcome.NeedBlocks need -> "路上需要 " + need.needed() + " 块垫脚方块，背包里只有 " + need.carried() + " 块";
            case NavOutcome.NeedsPermission permission when permission.kind().equals("risk") -> "路线要在岩浆上方冒险通过";
            case NavOutcome.NeedsPermission permission -> "路线要挖穿约 " + permission.blocks() + " 个可能是别人放置的方块";
            case NavOutcome.Unreachable unreachable -> switch (unreachable.reason()) {
                case "unloaded" -> "目标区域还没有加载";
                case "too_far" -> "目标太远或地形太复杂，没有找到路线";
                case "stuck" -> "移动时一直卡住";
                default -> "找不到能过去的路线";
            };
            case null -> "路径不可达";
        };
    }

    public boolean equip(Item item, EquipmentSlot slot) {
        if (npc.getItemBySlot(slot).is(item)) return true;
        for (int i = 0; i < npc.backpack().getContainerSize(); i++) {
            ItemStack stack = npc.backpack().getItem(i);
            if (!stack.is(item)) continue;
            ItemStack replacement = npc.backpack().removeItem(i, 1);
            ItemStack previous = npc.getItemBySlot(slot);
            npc.setItemSlot(slot, replacement);
            if (!previous.isEmpty()) storeOrDrop(previous);
            return true;
        }
        return false;
    }

    private void storeOrDrop(ItemStack stack) {
        ItemStack remainder = npc.backpack().addItem(stack);
        if (!remainder.isEmpty()) npc.spawnAtLocation(remainder);
    }

    private void speak(ActionPlan plan) {
        long now = npc.level().getGameTime();
        if (now - lastSpeechTick < 100 && !npc.brain().hasGoal()) return;
        lastSpeechTick = now;
        String text = switch (plan.argument()) {
            case "help" -> "附近有危险，请帮帮我！";
            case "greet" -> "你好，我是小杰。可以叫我跟随、守卫、砍树、挖石头或搭平台。";
            default -> "我是小杰，可以观察附近环境、去浅水里、采集并交付木头、挖地面或石头、攻击指定生物、跟随和守卫，也能搭 3×3 橡木平台。";
        };
        npc.say(text);
        npc.brain().stepFinished(plan, true, 0, "已说明能力");
        npc.brain().requestHandled();
    }

    private void finish(boolean success, String reason) { finish(success, reason, ""); }

    /** {@code code} is a stable machine-readable failure reason such as {@code need_blocks:3}; empty on success. */
    private void finish(boolean success, String reason, String code) {
        if (active == null) return;
        if (active.recovery()) {
            endRecovery(success || active.progress > 0, reason);
            return;
        }
        ActionPlan finishedPlan = active.plan;
        int progress = active.progress;
        String skill = active.plan.skill().name();
        clearCracks();
        navigator.stop();
        active = null;
        lastOutcome = (success ? "completed: " : "failed: ") + skill + " " + reason;
        npc.remember(lastOutcome);
        npc.tellOwner(reason);
        npc.brain().stepFinished(finishedPlan, success, progress, reason, code);
        npc.brain().requestHandled();
    }

    private void clearCracks() {
        if (active != null && active.blockTarget != null && !npc.level().isClientSide)
            npc.level().destroyBlockProgress(npc.getId(), active.blockTarget, -1);
        if (active != null && active.clearingTarget != null && !npc.level().isClientSide)
            npc.level().destroyBlockProgress(npc.getId(), active.clearingTarget, -1);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        // A recovery is re-derived from the next failed route after loading, so only its parent is stored.
        if (active != null) tag.put("active", (active.recovery() ? active.parent : active).save());
        if (suspended != null) tag.put("suspended", (suspended.recovery() ? suspended.parent : suspended).save());
        tag.putString("outcome", lastOutcome);
        return tag;
    }

    public void load(CompoundTag tag) {
        try {
            active = tag.contains("active") ? Task.load(tag.getCompound("active")) : null;
            suspended = tag.contains("suspended") ? Task.load(tag.getCompound("suspended")) : null;
            lastOutcome = tag.getString("outcome");
        } catch (IllegalArgumentException exception) {
            active = null;
            suspended = null;
            lastOutcome = "saved task was incompatible";
        }
    }
}
