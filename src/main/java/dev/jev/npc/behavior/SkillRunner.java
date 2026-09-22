package dev.jev.npc.behavior;

import dev.jev.npc.JevNpcMod;
import dev.jev.npc.entity.JevNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
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
        BlockPos blockTarget;
        Vec3 lastPosition;
        Task(ActionPlan plan) { this.plan = plan; }
        CompoundTag save() {
            CompoundTag tag = plan.save();
            tag.putInt("progress", progress);
            return tag;
        }
        static Task load(CompoundTag tag) {
            Task task = new Task(ActionPlan.load(tag));
            task.progress = Math.clamp(tag.getInt("progress"), 0, 64);
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
        if (plan.skill() == Skill.SPEAK) { speak(plan.argument()); return; }
        if (plan.skill() == Skill.EQUIP) {
            boolean result = equip(Items.IRON_CHESTPLATE, EquipmentSlot.CHEST);
            announceInstant(result ? "装备了铁胸甲" : "背包里没有铁胸甲");
            return;
        }
        if (plan.skill() == Skill.EAT) {
            if (npc.backpack().countItem(Items.BREAD) > 0) {
                npc.backpack().removeItemType(Items.BREAD, 1);
                npc.heal(6);
                announceInstant("吃了面包，恢复了 6 点生命值（Demo 规则）");
            } else announceInstant("没有面包");
            return;
        }
        if (preserveCurrent && active != null && active.plan.skill() != Skill.FLEE && active.plan.skill() != Skill.ATTACK) {
            suspended = active;
        } else if (!preserveCurrent) suspended = null;
        clearCracks();
        npc.getNavigation().stop();
        active = new Task(plan);
        lastOutcome = "running";
        npc.tellOwner("开始：" + plan.description());
    }

    private void announceInstant(String message) {
        npc.tellOwner(message);
        npc.remember(message);
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
            default -> finish(false, "不支持的持续技能");
        }
    }

    private static boolean isWork(Skill skill) { return skill == Skill.HARVEST || skill == Skill.MINE || skill == Skill.BUILD || skill == Skill.MOVE; }

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
        if (npc.distanceToSqr(target) < 2.5) {
            if (active.plan.skill() == Skill.FLEE && emergencyLocked()) { npc.getNavigation().stop(); return; }
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
        if (!(target instanceof Enemy) || target.distanceToSqr(npc) > 24 * 24) { finish(false, "目标不再是允许追击的敌人"); return; }
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
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos mutable : BlockPos.betweenClosed(center.offset(-8, -2, -8), center.offset(8, 6, 8))) {
            BlockPos position = mutable.immutable();
            if (!npc.level().hasChunkAt(position) || npc.distanceToSqr(Vec3.atCenterOf(position)) > 20 * 20) continue;
            BlockState state = npc.level().getBlockState(position);
            if (!(logs ? state.is(BlockTags.LOGS) && hasLeavesNearby(position) : state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE))) continue;
            if (!exposed(position) || !canChange(position)) continue;
            double distance = npc.distanceToSqr(Vec3.atCenterOf(position));
            if (distance < bestDistance) { bestDistance = distance; best = position; }
        }
        return Optional.ofNullable(best);
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
        if (!equip(logs ? Items.IRON_AXE : Items.IRON_PICKAXE, EquipmentSlot.MAINHAND)) {
            finish(false, "缺少所需工具"); return;
        }
        if (active.blockTarget == null || npc.level().getBlockState(active.blockTarget).isAir()) {
            clearCracks();
            active.blockTarget = findWorkBlock(active.plan.position(), logs).orElse(null);
            active.workTicks = 0;
            if (active.blockTarget == null) { finish(active.progress > 0, "区域内没有更多可触及的目标"); return; }
        }
        BlockPos target = active.blockTarget;
        if (!canChange(target)) { finish(false, "作业位置已不可修改"); return; }
        Vec3 center = Vec3.atCenterOf(target);
        npc.getLookControl().setLookAt(center.x, center.y, center.z);
        if (npc.getEyePosition().distanceToSqr(center) > 3.5 * 3.5) {
            navigate(Vec3.atBottomCenterOf(target), 1);
            return;
        }
        // Require line of sight to the actual block; do not mine through intervening terrain.
        var hit = npc.level().clip(new net.minecraft.world.level.ClipContext(npc.getEyePosition(), center,
            net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, npc));
        if (!hit.getBlockPos().equals(target)) { finish(false, "目标被其他方块遮挡"); return; }
        npc.getNavigation().stop();
        active.blockedTicks = 0;
        BlockState state = npc.level().getBlockState(target);
        if (!(logs ? state.is(BlockTags.LOGS) : state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE))) {
            finish(false, "方块已变化"); return;
        }
        active.workTicks++;
        if (active.workTicks % 8 == 0) npc.swing(InteractionHand.MAIN_HAND);
        npc.level().destroyBlockProgress(npc.getId(), target, Math.min(9, active.workTicks / 4));
        if (active.workTicks < 40) return;
        ItemStack tool = npc.getMainHandItem();
        List<ItemStack> drops = Block.getDrops(state, npc.serverLevel(), target, null, npc, tool);
        clearCracks();
        if (npc.level().destroyBlock(target, false, npc)) {
            drops.forEach(this::storeOrDrop);
            tool.hurtAndBreak(1, npc, EquipmentSlot.MAINHAND);
            active.progress++;
        } else { finish(false, "方块破坏失败"); return; }
        active.blockTarget = null;
        active.workTicks = 0;
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
        if (active.ticks % 20 != 1) return;
        if (active.lastPosition != null && npc.position().distanceToSqr(active.lastPosition) < 0.08) active.blockedTicks += 20;
        else active.blockedTicks = 0;
        active.lastPosition = npc.position();
        if (active.blockedTicks >= 160) { finish(false, "路径不可达或持续卡住"); return; }
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

    private void speak(String intent) {
        long now = npc.level().getGameTime();
        if (now - lastSpeechTick < 100) return;
        lastSpeechTick = now;
        String text = switch (intent) {
            case "help" -> "附近有危险，请帮帮我！";
            case "greet" -> "你好，我是小杰。可以叫我跟随、守卫、砍树、挖石头或搭平台。";
            default -> "我能跟随、守卫、撤退、砍树、挖石头、搭 3×3 橡木平台和装备铁胸甲。请告诉我具体要做什么。";
        };
        npc.say(text);
        npc.brain().requestHandled();
    }

    private void finish(boolean success, String reason) {
        if (active == null) return;
        String skill = active.plan.skill().name();
        clearCracks();
        npc.getNavigation().stop();
        active = null;
        lastOutcome = (success ? "completed: " : "failed: ") + skill + " " + reason;
        npc.remember(lastOutcome);
        npc.tellOwner(reason);
        npc.brain().requestHandled();
        npc.brain().event(success ? "task_completed" : "task_failed", lastOutcome, true);
    }

    private void clearCracks() {
        if (active != null && active.blockTarget != null && !npc.level().isClientSide)
            npc.level().destroyBlockProgress(npc.getId(), active.blockTarget, -1);
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
