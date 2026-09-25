package dev.jev.npc.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.jev.npc.behavior.ActionPlan;
import dev.jev.npc.behavior.Skill;
import dev.jev.npc.entity.JevNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded observation tools. Results contain real identities, never model-generated coordinates. */
public final class EnvironmentTools {
    private final JevNpcEntity npc;
    private final Map<String, ActionPlan> targets = new LinkedHashMap<>();
    private JsonObject observation = new JsonObject();
    private int scans;
    private long observedAt;

    public EnvironmentTools(JevNpcEntity npc) { this.npc = npc; }
    public JsonObject state() {
        JsonObject state = observation.deepCopy();
        state.addProperty("scan_count", scans);
        state.addProperty("age_ticks", scans == 0 ? 0 : npc.level().getGameTime() - observedAt);
        return state;
    }

    public Map<String, ActionPlan> options(AgentTask task, BlockPos anchor) {
        Map<String, ActionPlan> choices = new LinkedHashMap<>();
        GoalIntent intent = task.intent;
        if (intent == null) return choices;
        if (task.canComplete()) {
            add(choices, "finish_goal", Skill.FINISH, null, "", 1, "Finish the goal; code has verified its completion conditions.");
            return choices;
        }
        if (task.plan != null) {
            for (int i = 0; i < task.plan.stages().size(); i++) {
                if (i == task.stageIndex || task.stageComplete(i)) continue;
                GoalPlan.Stage stage = task.plan.stages().get(i);
                boolean persistent = java.util.Set.of("follow", "wait", "guard").contains(stage.method().verb());
                if (persistent && java.util.stream.IntStream.range(0, i).anyMatch(index -> !task.stageComplete(index))) continue;
                add(choices, "select_stage_" + i, Skill.CONTINUE, null, "", 1,
                    "Switch to stage " + i + ": " + stage.purpose() + ". Only if dependencies and the owner's ordering constraints permit. Preserve progress in other stages.");
            }
        }
        if (task.stageComplete()) return choices;
        if (intent.gathering() && intent.deliverToOwner() && !task.collected.isEmpty())
            add(choices, "deliver_collected", Skill.GIVE, null, "", 1,
                "Walk to the owner and deliver only this task's collected items. Prefer reaching requested amount first, but deliver partial results if no more targets remain.");
        boolean needsTargets = intent.gathering() && task.gathered < intent.amount()
            || intent.verb().equals("attack") || intent.verb().equals("go_to") && intent.place().equals("water");
        if (needsTargets) {
            if (scans < 2) add(choices, scans == 0 ? "observe_nearby" : "observe_wider", Skill.OBSERVE, anchor, "", 1,
                scans == 0 ? "Search nearby loaded terrain/entities for the goal; return grounded targets and environmental information."
                    : "Search a wider loaded area for alternative targets after missing, blocked or failed targets.");
            // Old observations are still evidence, but execution rechecks the current world.
            for (var entry : targets.entrySet()) {
                if (task.failedTargets.contains(entry.getKey())) continue;
                ActionPlan target = entry.getValue();
                if (target.position() != null && (intent.gathering()
                    ? !npc.skills().isWorkTarget(target.position(), intent.material()) : !isShallowWater(target.position()))) continue;
                if (target.target() != null && (!(npc.serverLevel().getEntity(target.target()) instanceof LivingEntity entity) || !entity.isAlive())) continue;
                choices.put(entry.getKey(), new ActionPlan(target.id(), target.skill(), target.position(), target.target(),
                    target.argument(), intent.gathering() ? intent.amount() - task.gathered : 1, target.description()));
            }
        } else if (!intent.gathering()) {
            switch (intent.verb()) {
                case "go_to" -> add(choices, "go_to_destination", Skill.MOVE,
                    intent.place().equals("home") ? npc.home() : anchor, "", 1, "Move to the requested " + intent.place() + " location.");
                case "follow" -> add(choices, "follow_owner", Skill.FOLLOW, null, "", 1, "Start following the owner continuously.");
                case "guard" -> add(choices, "guard_here", Skill.GUARD, anchor, "", 1, "Start guarding the requested location continuously.");
                case "wait" -> add(choices, "wait_here", Skill.WAIT, npc.blockPosition(), "", 1, "Stop and wait here.");
                case "equip" -> add(choices, "equip_armor", Skill.EQUIP, null, "", 1, "Equip the carried iron chestplate.");
                case "eat" -> add(choices, "eat_bread", Skill.EAT, null, "", 1, "Eat one carried bread.");
                case "speak" -> add(choices, "explain_capabilities", Skill.SPEAK, null, "capabilities", 1, "Introduce yourself and explain supported capabilities with a fixed message.");
                case "build" -> add(choices, "build_platform", Skill.BUILD, anchor.offset(3, 0, 0), "oak_platform_3x3", 9, "Build the supported 3x3 oak platform.");
                default -> {}
            }
        }
        choices.keySet().removeAll(task.failedTargets);
        add(choices, "cannot_complete", Skill.REPORT, null, "", 1,
            "Report that the goal cannot be completed with these tools/results, or needs clarification. Do not use merely because a target has not been searched yet.");
        return choices;
    }

    public void observe(AgentTask task, BlockPos anchor) {
        int radius = ++scans == 1 ? 8 : 16;
        observedAt = npc.level().getGameTime();
        targets.clear();
        observation = new JsonObject();
        observation.addProperty("radius_blocks", radius);
        observation.addProperty("center", anchor.toShortString());
        observation.addProperty("coverage", "Loaded chunks only; terrain vertical range -3 to +6; shallow standable water only. Path reachability not yet verified.");
        JsonArray found = new JsonArray();
        GoalIntent intent = task.intent;
        if (intent.verb().equals("attack")) {
            List<LivingEntity> entities = npc.level().getEntitiesOfClass(LivingEntity.class,
                npc.getBoundingBox().inflate(radius), entity -> entity != npc && !(entity instanceof Player)
                    && entity.isAlive() && npc.hasLineOfSight(entity));
            entities.sort(Comparator.comparingDouble(npc::distanceToSqr));
            for (LivingEntity entity : entities.stream().limit(8).toList()) {
                String id = "entity_" + entity.getUUID();
                String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
                JsonObject data = targetData(id, entity.blockPosition());
                data.addProperty("entity_type", type);
                data.addProperty("name", entity.getName().getString());
                found.add(data);
                targets.put(id, new ActionPlan(id, Skill.ATTACK, null, entity.getUUID(), "owner_target", 1,
                    "Attack the owner-requested non-player target " + type + "; " + data));
            }
        } else {
            List<BlockPos> positions = new ArrayList<>();
            for (BlockPos cursor : BlockPos.betweenClosed(anchor.offset(-radius, -3, -radius), anchor.offset(radius, 6, radius))) {
                BlockPos pos = cursor.immutable();
                if (!npc.level().hasChunkAt(pos) || !npc.level().getWorldBorder().isWithinBounds(pos)
                    || npc.distanceToSqr(Vec3.atCenterOf(pos)) > 24 * 24) continue;
                if (intent.gathering() ? npc.skills().observedWorkTarget(pos, intent.material()) : isShallowWater(pos)) positions.add(pos);
            }
            observation.addProperty("detected_blocks", positions.size());
            observation.addProperty("blocked_by_permissions", intent.gathering()
                ? positions.stream().filter(pos -> !npc.skills().isWorkTarget(pos, intent.material())).count() : 0);
            observation.addProperty("previously_failed_targets", task.failedTargets.size());
            positions.sort(Comparator.comparingDouble(pos -> npc.distanceToSqr(Vec3.atCenterOf(pos))));
            for (BlockPos pos : positions) {
                String id = (intent.gathering() ? "work_" : "water_") + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
                if (task.failedTargets.contains(id) || intent.gathering() && !npc.skills().isWorkTarget(pos, intent.material())) continue;
                JsonObject data = targetData(id, pos);
                if (intent.gathering()) data.addProperty("visibility", npc.skills().workVisibility(pos));
                data.addProperty("block", BuiltInRegistries.BLOCK.getKey(npc.level().getBlockState(pos).getBlock()).toString());
                found.add(data);
                Skill skill = intent.gathering() ? intent.verb().equals("harvest") ? Skill.HARVEST : Skill.MINE : Skill.MOVE;
                targets.put(id, new ActionPlan(id, skill, pos, null, intent.gathering() ? intent.material() : "water", 1,
                    (intent.gathering() ? "Collect requested " + intent.material() + " blocks (clear nearby natural leaf obstructions when harvesting logs) around " : "Enter shallow water at ") + pos.toShortString()
                        + "; distance=" + Math.round(Math.sqrt(npc.distanceToSqr(Vec3.atCenterOf(pos)))) + "; path not yet verified."));
                if (found.size() == 6) break;
            }
        }
        observation.add("targets", found);
        observation.addProperty("offered_targets", targets.size());
        task.feedback(scans == 1 ? "observe_nearby" : "observe_wider", true, found.size(), observation.toString());
    }

    public String unavailableMessage(boolean partial) {
        if (observation.has("detected_blocks") && observation.get("detected_blocks").getAsInt() > 0) {
            if (observation.get("blocked_by_permissions").getAsInt() == observation.get("detected_blocks").getAsInt())
                return "发现了目标方块，但当前方块修改权限或游戏规则不允许采集。";
            return (partial ? "已完成部分采集。" : "") + "附近有目标方块，但目前无法接近或处理遮挡，任务尚未完成。";
        }
        return "本次搜索范围内没有发现合适目标；搜索范围以外的环境还不清楚。";
    }

    private JsonObject targetData(String id, BlockPos pos) {
        JsonObject data = new JsonObject();
        data.addProperty("id", id);
        data.addProperty("position", pos.toShortString());
        data.addProperty("distance", Math.sqrt(npc.distanceToSqr(Vec3.atCenterOf(pos))));
        if (npc.owner() != null) {
            Vec3 direction = Vec3.atCenterOf(pos).subtract(npc.owner().getEyePosition()).normalize();
            data.addProperty("owner_look_alignment", npc.owner().getLookAngle().dot(direction));
        }
        return data;
    }

    public boolean isShallowWater(BlockPos pos) {
        return npc.level().hasChunkAt(pos) && npc.level().getFluidState(pos).is(FluidTags.WATER)
            && npc.level().getBlockState(pos.above()).isAir()
            && npc.level().getBlockState(pos.below()).isFaceSturdy(npc.level(), pos.below(), net.minecraft.core.Direction.UP);
    }

    private static void add(Map<String, ActionPlan> map, String id, Skill skill, BlockPos pos, String argument, int count, String description) {
        map.put(id, new ActionPlan(id, skill, pos, null, argument, count, description));
    }
}
