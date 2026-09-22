package dev.jev.npc.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.jev.npc.JevNpcMod;
import dev.jev.npc.behavior.ActionPlan;
import dev.jev.npc.behavior.Skill;
import dev.jev.npc.config.NpcConfig;
import dev.jev.npc.entity.JevNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Event-driven decision scheduling; all methods except HTTP completion run on the server thread. */
public final class NpcBrain {
    private final JevNpcEntity npc;
    private final EventInbox events = new EventInbox();
    private final DecisionGate gate = new DecisionGate();
    private long nextDecisionTick;
    private long lastIdleTick;
    private String weather;
    private String status = "local / no decision yet";
    private String ownerRequest = "";
    private BlockPos requestAnchor;
    private boolean held;

    public NpcBrain(JevNpcEntity npc) { this.npc = npc; }
    public String status() { return status; }
    public void invalidate() { gate.invalidate(); }
    public void requestHandled() { ownerRequest = ""; }
    public void hold() {
        held = true;
        ownerRequest = "";
        gate.invalidate();
        events.drain();
        status = "PAUSED: 手动控制";
    }
    public void wake() {
        held = false;
        nextDecisionTick = 0;
        event("manual_think", "Review the current situation", true);
    }

    public void event(String kind, String detail, boolean invalidatesPrevious) {
        if (invalidatesPrevious) gate.invalidate();
        events.add(kind, detail.length() > 500 ? detail.substring(0, 500) : detail, npc.tickCount);
    }

    public void chat(ServerPlayer player, String message) {
        if (!npc.isOwner(player) || player.level() != npc.level() || player.distanceToSqr(npc) > 32 * 32) return;
        held = false;
        ownerRequest = message.length() > 300 ? message.substring(0, 300) : message;
        requestAnchor = player.blockPosition();
        npc.remember("Owner said: " + ownerRequest);
        event("owner_chat", ownerRequest, true);
        npc.tellOwner("已收到指令，等待 Jev 判断。当前动作会继续执行。");
    }

    public void tick() {
        if (held) return;
        NpcConfig config = JevNpcMod.config();
        ServerPlayer owner = npc.owner();
        if (owner == null || owner.level() != npc.level() || owner.distanceToSqr(npc) > 48 * 48) return;
        long tick = npc.tickCount;
        if (tick % 20 == 0) {
            String current = (npc.level().isThundering() ? "thunder" : npc.level().isRaining() ? "rain" : "clear")
                + (npc.level().isNight() ? "/night" : "/day");
            if (weather != null && !weather.equals(current)) event("weather_changed", current, false);
            weather = current;
        }
        if (tick - lastIdleTick >= config.idleDecisionTicks) {
            event("periodic_check", "Review current activity; continuing is usually appropriate", false);
            lastIdleTick = tick;
        }
        if (!events.ready(tick, config.eventDebounceTicks) || gate.inFlight() || tick < nextDecisionTick) return;
        if (!config.enabled || config.effectiveKey().isBlank()) {
            status = config.enabled ? "NO_KEY: 本地技能模式" : "DISABLED: 本地技能模式";
            events.drain();
            return;
        }
        long nowMs = monotonicMs();
        if (!JevNpcMod.budget().acquire(nowMs, config.maxRequestsPerMinute)) {
            status = "LOCAL_RATE_LIMIT: 等待预算";
            nextDecisionTick = tick + 20;
            return;
        }
        Map<String, ActionPlan> options = options();
        List<Candidate> candidates = options.values().stream().map(ActionPlan::candidate).toList();
        Map<String, String> triggeringEvents = events.drain();
        JsonObject state = snapshot(triggeringEvents);
        DecisionGate.Ticket ticket = gate.begin(nowMs).orElseThrow();
        nextDecisionTick = tick + config.decisionCooldownTicks;
        status = "REQUESTING";
        var server = npc.getServer();
        var world = npc.level();
        JevNpcMod.client().decide(config.effectiveKey(), config.model, state, candidates, config.requestTimeoutMs)
            .whenComplete((decision, failure) -> server.execute(() -> {
                boolean valid = gate.complete(ticket, monotonicMs(), config.maxResultAgeMs);
                if (held) { status = "PAUSED: 手动控制"; return; }
                if (!valid || npc.isRemoved() || !npc.isAlive() || npc.level() != world) {
                    status = "STALE: 丢弃旧判断";
                    return;
                }
                ServerPlayer currentOwner = npc.owner();
                if (currentOwner == null || currentOwner.level() != world || currentOwner.distanceToSqr(npc) > 48 * 48) {
                    status = "OWNER_UNAVAILABLE";
                    return;
                }
                if (failure != null) {
                    status = JevClient.errorCode(failure);
                    nextDecisionTick = npc.tickCount + (status.equals("HTTP_401") || status.equals("HTTP_403") ? 1200 : 200);
                    npc.tellOwner("Jev 未完成判断（" + status + "），继续本地行为。/jev status 可查看状态。");
                    JevNpcMod.LOGGER.warn("NPC {} Jev failure: {}", npc.getUUID(), status);
                    return;
                }
                status = decision.candidateId() + " | " + decision.elapsedMs() + "ms | confidence="
                    + String.format(java.util.Locale.ROOT, "%.2f", decision.confidence()) + " | tokens=" + decision.inputTokens();
                JevNpcMod.LOGGER.info("NPC {} decision={} model={} latencyMs={} inputTokens={} confidence={}",
                    npc.getUUID(), decision.candidateId(), decision.model(), decision.elapsedMs(), decision.inputTokens(), decision.confidence());
                if (config.debugToOwner) npc.tellOwner("Jev: " + status);
                if (decision.confidence() < config.minimumConfidence || npc.skills().emergencyLocked()) {
                    status += " | kept current task";
                    return;
                }
                ActionPlan plan = options.get(decision.candidateId());
                if (plan == null) return;
                boolean interrupt = plan.skill() == Skill.ATTACK || plan.skill() == Skill.FLEE;
                npc.skills().start(plan, interrupt);
            }));
    }

    public void resetAfterReload() {
        gate.invalidate();
        nextDecisionTick = 0;
        event("configuration_reloaded", "Re-evaluate current activity", false);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("held", held);
        tag.putString("ownerRequest", ownerRequest);
        if (requestAnchor != null) tag.putLong("requestAnchor", requestAnchor.asLong());
        return tag;
    }

    public void load(CompoundTag tag) {
        held = tag.getBoolean("held");
        String savedRequest = tag.getString("ownerRequest");
        ownerRequest = savedRequest.length() > 300 ? savedRequest.substring(0, 300) : savedRequest;
        requestAnchor = tag.contains("requestAnchor") ? BlockPos.of(tag.getLong("requestAnchor")) : null;
        status = held ? "PAUSED: 手动控制" : "loaded; awaiting event";
    }

    public Map<String, ActionPlan> options() {
        Map<String, ActionPlan> result = new LinkedHashMap<>();
        BlockPos anchor = requestAnchor == null ? npc.home() : requestAnchor;
        add(result, "continue_current", Skill.CONTINUE, null, null, "", 1, "Keep the current task and do not restart it. If idle, remain idle.");
        add(result, "wait_here", Skill.WAIT, npc.blockPosition(), null, "", 1, "Stop other work and wait here until a new instruction.");
        add(result, "follow_owner", Skill.FOLLOW, null, npc.ownerId(), "", 1, "Follow the owner, maintaining a comfortable distance.");
        add(result, "guard_here", Skill.GUARD, anchor, null, "", 1, "Guard the owner's requested location and fight hostile mobs within 12 blocks of it.");
        add(result, "return_home", Skill.MOVE, npc.home(), null, "", 1, "Move back to the saved home location.");
        add(result, "move_to_owner", Skill.MOVE, anchor, null, "", 1, "Move once to the location where the owner made the latest request.");
        add(result, "explain_capabilities", Skill.SPEAK, null, null, "capabilities", 1, "Explain supported abilities with a fixed chat message when the request is unsupported or unclear.");
        add(result, "greet", Skill.SPEAK, null, null, "greet", 1, "Greet the owner in chat while keeping the current movement/work task.");
        if (npc.skills().hasSuspendedTask()) add(result, "resume_task", Skill.RESUME, null, null, "", 1, "Resume the task interrupted by danger: " + npc.skills().suspendedSummary());
        if (npc.backpack().countItem(Items.BREAD) > 0 && npc.getHealth() < npc.getMaxHealth())
            add(result, "eat_bread", Skill.EAT, null, null, "", 1, "Eat one carried bread to regain 6 health, keeping current task.");
        if (npc.backpack().countItem(Items.IRON_CHESTPLATE) > 0 && !npc.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE))
            add(result, "equip_armor", Skill.EQUIP, null, null, "", 1, "Equip the iron chestplate already in the backpack, keeping current task.");
        LivingEntity enemy = npc.skills().nearestEnemy(16);
        if (enemy != null) {
            add(result, "attack_enemy", Skill.ATTACK, null, enemy.getUUID(), "", 1,
                "Pause current work and attack the visible hostile mob " + BuiltInRegistries.ENTITY_TYPE.getKey(enemy.getType()) + "; target=" + enemy.getUUID());
            Vec3Escape.add(result, npc, enemy);
            add(result, "call_for_help", Skill.SPEAK, null, null, "help", 1, "Ask nearby players for help in chat while keeping current task.");
        }
        // World-changing work is proposed only while handling an explicit owner request.
        if (!ownerRequest.isBlank() && JevNpcMod.config().allowBlockChanges) {
            if (npc.skills().findWorkBlock(anchor, true).isPresent())
                add(result, "harvest_logs", Skill.HARVEST, anchor, null, "", 4, "Collect up to 4 nearby reachable natural logs in the requested area, using the carried axe.");
            if (npc.skills().findWorkBlock(anchor, false).isPresent())
                add(result, "mine_stone", Skill.MINE, anchor, null, "", 4, "Mine up to 4 exposed stone/cobblestone blocks in the requested area, using the carried pickaxe.");
            if (npc.backpack().countItem(Items.OAK_PLANKS) >= 9)
                add(result, "build_platform", Skill.BUILD, anchor.offset(3, 0, 0), null, "oak_platform_3x3", 9,
                    "Build a 3x3 oak plank platform three blocks east of the owner's request location, consuming 9 carried planks; never overwrite existing solid blocks.");
        }
        return result;
    }

    private static void add(Map<String, ActionPlan> map, String id, Skill skill, BlockPos position,
                            java.util.UUID target, String argument, int count, String description) {
        map.put(id, new ActionPlan(id, skill, position, target, argument, count, description));
    }

    private JsonObject snapshot(Map<String, String> triggeringEvents) {
        JsonObject state = new JsonObject();
        state.addProperty("personality", switch (npc.personality()) {
            case "brave" -> "Brave, protective of the owner, willing to fight hostile mobs, keeps promises.";
            case "diligent" -> "Diligent worker, finishes commitments, dislikes unnecessary interruptions, values safety.";
            default -> "Cautious, friendly, values survival and promises; prefers help or retreat when outmatched.";
        });
        state.addProperty("current_task", npc.skills().summary());
        state.addProperty("suspended_task", npc.skills().suspendedSummary());
        state.addProperty("owner_request", ownerRequest.isBlank() ? "No new outstanding owner request" : ownerRequest);
        JsonObject observed = new JsonObject();
        observed.addProperty("health", npc.getHealth());
        observed.addProperty("health_condition", npc.getHealth() <= 8 ? "critical" : npc.getHealth() < 20 ? "injured" : "healthy");
        observed.addProperty("weather", weather == null ? "unknown" : weather);
        observed.addProperty("standing_in_rain", npc.level().isRainingAt(npc.blockPosition()));
        observed.addProperty("owner_distance_blocks", Math.round(npc.distanceTo(npc.owner())));
        LivingEntity enemy = npc.skills().nearestEnemy(16);
        observed.addProperty("visible_enemy", enemy == null ? "none" : BuiltInRegistries.ENTITY_TYPE.getKey(enemy.getType()).toString());
        if (enemy != null) observed.addProperty("enemy_distance_blocks", Math.round(npc.distanceTo(enemy)));
        JsonArray inventory = new JsonArray();
        for (ItemStack stack : npc.backpack().getItems()) {
            if (!stack.isEmpty()) inventory.add(BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x " + stack.getCount());
        }
        observed.add("backpack", inventory);
        state.add("observations", observed);
        JsonObject eventJson = new JsonObject();
        triggeringEvents.forEach(eventJson::addProperty);
        state.add("events", eventJson);
        JsonArray memories = new JsonArray();
        npc.memories().forEach(memories::add);
        state.add("recent_memory", memories);
        state.addProperty("constraints", "Only the owner can command this NPC. Never attack players. "
            + "Only supplied actions are executable. Building supports one fixed 3x3 oak platform, not arbitrary structures. "
            + "Long tasks run locally. Weather alone need not interrupt work. Do not repeat a completed owner request.");
        return state;
    }

    private static long monotonicMs() { return System.nanoTime() / 1_000_000; }

    private static final class Vec3Escape {
        static void add(Map<String, ActionPlan> options, JevNpcEntity npc, LivingEntity enemy) {
            var escape = net.minecraft.world.entity.ai.util.DefaultRandomPos.getPosAway(npc, 12, 5, enemy.position());
            if (escape != null) NpcBrain.add(options, "flee_enemy", Skill.FLEE, BlockPos.containing(escape), null, "", 1,
                "Pause work and retreat away from the visible enemy to a locally selected destination.");
        }
    }
}
