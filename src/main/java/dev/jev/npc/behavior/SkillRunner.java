package dev.jev.npc.behavior;

import dev.jev.npc.JevNpcMod;
import dev.jev.npc.entity.JevNpcEntity;
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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Owns navigation, work progress and interruption. It never calls a language model. */
public final class SkillRunner {
    private final JevNpcEntity npc;
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
        BlockPos clearingTarget;
        BlockPos approachTarget;
        BlockPos blockTarget;
        Vec3 approach;
        Vec3 lastPosition;
        final Set<BlockPos> skipped = new HashSet<>();
        Task(ActionPlan plan) { this.plan = plan; }
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

    public SkillRunner(JevNpcEntity npc) { this.npc = npc; }
    public String summary() {
        return active == null ? "idle (" + lastOutcome + ")" : active.plan.skill() + " " + active.progress + "/" + active.plan.count();
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
        npc.getNavigation().stop();
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
        npc.getNavigation().stop();
        lastOutcome = "cancelled by owner";
    }

    public void resume() {
        if (suspended == null) return;
        clearCracks();
        npc.getNavigation().stop();
        active = suspended;
        suspended = null;
        active.blockedTicks = 0;
        active.workTicks = 0;
        active.lastPosition = null;
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
        if (active == null) return;
        // Do not operate on a world for an absent owner, including after reload/dimension changes.
        ServerPlayer owner = npc.owner();
        if (owner == null || owner.level() != npc.level() || owner.distanceToSqr(npc) > 64 * 64) {
            npc.getNavigation().stop();
            return;
        }
        active.ticks++;
        if (isWork(active.plan.skill()) && active.ticks > 2400) { finish(false, "任务超时"); return; }
        switch (active.plan.skill()) {
            case WAIT -> npc.getNavigation().stop();
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

    private static boolean isWork(Skill skill) { return skill == Skill.HARVEST || skill == Skill.MINE || skill == Skill.BUILD || skill == Skill.MOVE || skill == Skill.GIVE; }

    private void follow() {
        ServerPlayer owner = npc.owner();
        if (npc.distanceToSqr(owner) <= 3 * 3) {
            npc.getNavigation().stop();
            active.blockedTicks = 0;
            npc.getLookControl().setLookAt(owner, 30, 30);
        } else navigate(owner.position(), 1.05);
    }

    private void move() {
        if (active.plan.position() == null) { finish(false, "没有目的地"); return; }
        Vec3 target = Vec3.atBottomCenterOf(active.plan.position());
        boolean water = active.plan.argument().equals("water");
        if (water && !npc.level().getFluidState(active.plan.position()).is(FluidTags.WATER)) {
            finish(false, "目标位置已经没有水"); return;
        }
        double horizontalDistance = Math.pow(npc.getX() - target.x, 2) + Math.pow(npc.getZ() - target.z, 2);
        if (water ? horizontalDistance < 0.09 && npc.isInWater() : npc.distanceToSqr(target) < 2.5) {
            if (active.plan.skill() == Skill.FLEE && emergencyLocked()) { npc.getNavigation().stop(); return; }
            npc.setDeltaMovement(0, npc.getDeltaMovement().y, 0);
            finish(true, "已到达目的地");
        } else navigate(target, active.plan.skill() == Skill.FLEE ? 1.35 : 1.0);
    }

    private void guard() {
        BlockPos anchor = active.plan.position();
        if (anchor == null) { finish(false, "缺少守卫位置"); return; }
        LivingEntity enemy = nearestEnemy(10);
        if (enemy != null && enemy.distanceToSqr(Vec3.atCenterOf(anchor)) <= 12 * 12) {
            combat(enemy);
        } else if (npc.distanceToSqr(Vec3.atBottomCenterOf(anchor)) > 2.5) {
            navigate(Vec3.atBottomCenterOf(anchor), 1);
        } else {
            active.blockedTicks = 0;
            npc.getNavigation().stop();
        }
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
            npc.getNavigation().stop();
            active.blockedTicks = 0;
            if (active.ticks % 20 == 0) {
                npc.swing(InteractionHand.MAIN_HAND);
                npc.doHurtTarget(target);
            }
        } else navigate(target.position(), 1.15);
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

    private Optional<BlockPos> findWorkBlock(BlockPos center, String material, Set<BlockPos> skipped) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos mutable : BlockPos.betweenClosed(center.offset(-8, -2, -8), center.offset(8, 6, 8))) {
            BlockPos position = mutable.immutable();
            if (skipped.contains(position) || npc.distanceToSqr(Vec3.atCenterOf(position)) > 20 * 20
                || !isWorkTarget(position, material)) continue;
            double distance = npc.distanceToSqr(Vec3.atCenterOf(position));
            if (distance < bestDistance) { bestDistance = distance; best = position; }
        }
        return Optional.ofNullable(best);
    }

    public boolean isWorkTarget(BlockPos position, String material) {
        return observedWorkTarget(position, material) && canChange(position);
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
            default -> state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE);
        };
    }

    private void skipWorkBlock() {
        clearCracks();
        if (active.blockTarget != null) {
            JevNpcMod.LOGGER.info("Jev work skip target={} npcPosition={} approach={}", active.blockTarget.toShortString(), npc.position(), active.approach);
            active.skipped.add(active.blockTarget);
        }
        active.blockTarget = null;
        active.approach = null;
        active.approachTarget = null;
        active.clearingTarget = null;
        active.leafTicks = 0;
        active.workTicks = 0;
        active.blockedTicks = 0;
        active.lastPosition = null;
        npc.getNavigation().stop();
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

    private boolean canChange(BlockPos position) {
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
        String material = logs ? "log" : active.plan.argument().equals("ground") ? "ground" : "stone";
        if (!equip(logs ? Items.IRON_AXE : Items.IRON_PICKAXE, EquipmentSlot.MAINHAND)) {
            finish(false, "缺少所需工具"); return;
        }
        if (active.blockTarget == null || npc.level().getBlockState(active.blockTarget).isAir()) {
            clearCracks();
            active.blockTarget = findWorkBlock(active.plan.position(), material, active.skipped).orElse(null);
            active.approach = null;
            active.approachTarget = null;
            active.clearingTarget = null;
            active.leafTicks = 0;
            active.workTicks = 0;
            if (active.blockTarget == null) { finish(false, "区域内没有更多可触及的目标，已采集 " + active.progress + " 个"); return; }
        }
        BlockPos target = active.blockTarget;
        if (!canChange(target) || !matchesMaterial(npc.level().getBlockState(target), material)) { skipWorkBlock(); return; }
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
        npc.getNavigation().stop();
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
                npc.brain().recordCollected(copy);
                if (!remainder.isEmpty()) npc.spawnAtLocation(remainder);
            }
            tool.hurtAndBreak(1, npc, EquipmentSlot.MAINHAND);
            active.progress++;
        } else { finish(false, "方块破坏失败"); return; }
        active.blockTarget = null;
        active.approach = null;
        active.approachTarget = null;
        active.clearingTarget = null;
        active.leafTicks = 0;
        active.workTicks = 0;
    }

    private void approachWorkBlock(BlockPos target) {
        if (!target.equals(active.approachTarget)) {
            active.approach = null;
            active.approachTarget = target;
            active.lastPosition = null;
            active.blockedTicks = 0;
        }
        if (active.approach == null) active.approach = workApproach(target);
        if (active.approach == null || npc.distanceToSqr(active.approach) < 0.16) { skipWorkBlock(); return; }
        navigate(active.approach, 1);
    }

    private boolean canClearLeaf(BlockPos leaf, BlockPos log) {
        if (active.clearedLeaves >= 24 || leaf.distSqr(log) > 4 * 4 || !canChange(leaf)) return false;
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
        npc.getNavigation().stop();
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
        active.approach = null;
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

    private Vec3 workApproach(BlockPos target) {
        List<BlockPos> spots = new java.util.ArrayList<>();
        for (BlockPos cursor : BlockPos.betweenClosed(target.offset(-3, -4, -3), target.offset(3, 1, 3))) {
            BlockPos pos = cursor.immutable();
            if (!npc.level().hasChunkAt(pos) || !npc.level().getWorldBorder().isWithinBounds(pos)
                || !npc.level().getBlockState(pos).isAir() || !npc.level().getBlockState(pos.above()).isAir()
                || !npc.level().getBlockState(pos.below()).isFaceSturdy(npc.level(), pos.below(), Direction.UP)) continue;
            Vec3 feet = Vec3.atBottomCenterOf(pos);
            Vec3 eye = feet.add(0, npc.getEyeHeight(), 0);
            if (eye.distanceToSqr(Vec3.atCenterOf(target)) <= 3.5 * 3.5 && seesBlock(eye, target)) spots.add(pos);
        }
        spots.sort(Comparator.comparingDouble(pos -> npc.distanceToSqr(Vec3.atBottomCenterOf(pos))));
        for (BlockPos pos : spots) {
            var path = npc.getNavigation().createPath(pos, 0);
            if (path != null && path.canReach()) return Vec3.atBottomCenterOf(pos);
        }
        return null;
    }

    private void give() {
        ServerPlayer owner = npc.owner();
        if (npc.distanceToSqr(owner) > 3 * 3) { navigate(owner.position(), 1); return; }
        npc.getNavigation().stop();
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
        if (!canChange(target)) { finish(false, "建筑位置不可修改"); return; }
        if (npc.level().getBlockState(target).is(Blocks.OAK_PLANKS)) { active.progress++; return; }
        if (!npc.level().getBlockState(target).isAir()) { finish(false, "建筑位置被占用，不会覆盖原有方块"); return; }
        if (npc.backpack().countItem(Items.OAK_PLANKS) == 0) { finish(false, "橡木木板不足"); return; }
        Vec3 center = Vec3.atCenterOf(target);
        if (npc.getEyePosition().distanceToSqr(center) > 4 * 4) {
            navigate(Vec3.atBottomCenterOf(origin.west()), 1);
            return;
        }
        npc.getNavigation().stop();
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

    private void navigate(Vec3 destination, double speed) {
        if (!npc.level().hasChunkAt(BlockPos.containing(destination))) { finish(false, "目标区块未加载"); return; }
        if (npc.getNavigation().isDone() && npc.distanceToSqr(destination) < 2.25)
            npc.getMoveControl().setWantedPosition(destination.x, destination.y, destination.z, speed);
        if (active.ticks % 20 != 1) return;
        if (active.lastPosition != null && npc.position().distanceToSqr(active.lastPosition) < 0.08) active.blockedTicks += 20;
        else active.blockedTicks = 0;
        active.lastPosition = npc.position();
        if (active.blockedTicks >= 160) {
            if (active.plan.skill() == Skill.HARVEST || active.plan.skill() == Skill.MINE) skipWorkBlock();
            else finish(false, "路径不可达或持续卡住");
            return;
        }
        npc.getNavigation().moveTo(destination.x, destination.y, destination.z, speed);
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

    private void finish(boolean success, String reason) {
        if (active == null) return;
        ActionPlan finishedPlan = active.plan;
        int progress = active.progress;
        String skill = active.plan.skill().name();
        clearCracks();
        npc.getNavigation().stop();
        active = null;
        lastOutcome = (success ? "completed: " : "failed: ") + skill + " " + reason;
        npc.remember(lastOutcome);
        npc.tellOwner(reason);
        npc.brain().stepFinished(finishedPlan, success, progress, reason);
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
        if (active != null) tag.put("active", active.save());
        if (suspended != null) tag.put("suspended", suspended.save());
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
