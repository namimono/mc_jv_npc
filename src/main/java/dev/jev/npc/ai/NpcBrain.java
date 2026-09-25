package dev.jev.npc.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.jev.npc.JevNpcMod;
import dev.jev.npc.behavior.ActionPlan;
import dev.jev.npc.behavior.Skill;
import dev.jev.npc.config.NpcConfig;
import dev.jev.npc.entity.JevNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Event-driven decision scheduling; all methods except HTTP completion run on the server thread. */
public final class NpcBrain {
    private final JevNpcEntity npc;
    private final EventInbox events = new EventInbox();
    private final DecisionGate gate = new DecisionGate();
    private long nextDecisionTick;
    private long lastIdleTick;
    private long lastAutonomyTick;
    private long idleSince;
    private List<Drives.Need> needs = List.of();
    private final Conversation conversation = new Conversation();
    private long conversationTurn;
    /** A failed initiative is not offered again until this tick, so one blocked need cannot loop. */
    private final Map<String, Long> initiativeCooldowns = new java.util.HashMap<>();
    private static final Set<String> INITIATIVES = Set.of("stock_blocks", "stock_wood", "rejoin_owner", "wander");
    private String weather;
    private String status = "local / no decision yet";
    private String ownerRequest = "";
    private BlockPos requestAnchor;
    private boolean held;
    private AgentTask task;
    private EnvironmentTools environment;
    private String activeStep;
    private ActionPlan escalated;
    private JsonObject lastTask = new JsonObject();

    public boolean hasGoal() { return task != null; }
    public JsonObject taskState() { return task == null ? lastTask.deepCopy() : task.state(); }
    public Map<String, Integer> collectedItems() { return task == null ? Map.of() : Map.copyOf(task.collected); }
    public void recordCollected(ItemStack stack) {
        if (task != null && activeStep != null && !stack.isEmpty()) {
            task.collected.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            task.delivered = false;
        }
    }
    public void recordDelivered(String item, int count) {
        if (task != null) task.collected.computeIfPresent(item, (key, old) -> old <= count ? null : old - count);
    }

    public void stepFinished(ActionPlan plan, boolean success, int progress, String reason) {
        stepFinished(plan, success, progress, reason, "");
    }

    /** {@code code} is the machine-readable failure reason from the skill layer, e.g. {@code need_blocks:3}. */
    public void stepFinished(ActionPlan plan, boolean success, int progress, String reason, String code) {
        String detail = code.isEmpty() ? reason : reason + " [" + code + "]";
        if (task == null || !plan.id().equals(activeStep)) {
            if (!success && INITIATIVES.contains(plan.id())) initiativeCooldowns.put(plan.id(), (long) npc.tickCount + 1200);
            event(success ? "task_completed" : "task_failed", detail, true);
            return;
        }
        activeStep = null;
        task.feedback(plan.id(), success, progress, detail);
        if (!success) task.failedTargets.add(plan.id());
        if (plan.skill() == Skill.HARVEST || plan.skill() == Skill.MINE) task.gathered += progress;
        else if (plan.skill() == Skill.GIVE) task.delivered = success && task.collected.isEmpty();
        else if (success) task.actionSucceeded = true;
        JevNpcMod.LOGGER.info("Jev tool result goal={} round={} tool={} success={} progress={} detail={}",
            task.id, task.rounds, plan.id(), success, progress, detail);
        if (code.startsWith("needs_permission:") && askPermission(plan, code.substring("needs_permission:".length()), reason)) return;
        event("tool_result", detail, true);
    }

    public Set<String> grants() { return task == null ? Set.of() : Set.copyOf(task.grants); }

    /** Value judgments (risk, breaking built blocks) belong to the owner; the goal waits for the answer. */
    private boolean askPermission(ActionPlan plan, String kind, String reason) {
        if (task.grants.contains(kind)) return false;
        String prompt = kind.equals("risk")
            ? reason + "，有点危险。要我冒险过去吗？（回复“可以”或“不要”）"
            : reason + "。可以挖穿吗？（回复“可以”或“不要”）";
        String fallback = kind.equals("risk") && npc.personality().equals("brave") ? "yes" : "no";
        long now = npc.level().getGameTime();
        escalated = plan;
        npc.speech().ask(new Communicator.Question(kind, prompt,
            Communicator.yesNo("The owner agrees or permits it", "The owner refuses or wants it avoided"),
            fallback, now + JevNpcMod.config().questionTimeoutTicks), now);
        status = "WAITING_FOR_OWNER: " + kind;
        JevNpcMod.LOGGER.info("Jev question goal={} kind={} fallback={} plan={}", task.id, kind, fallback, plan.id());
        return true;
    }

    private void resolveQuestion(Communicator.Question question, String answer, String source) {
        npc.speech().resolve();
        ActionPlan plan = escalated;
        escalated = null;
        if (question == null || task == null || plan == null) return;
        boolean allowed = answer.equals("yes");
        JevNpcMod.LOGGER.info("Jev answer goal={} kind={} answer={} source={}", task.id, question.kind(), answer, source);
        task.feedback("owner_permission", allowed, 0, (allowed ? "owner allowed " : "owner refused ") + question.kind() + " (" + source + ")");
        if (!allowed) {
            if (source.equals("new_instruction")) return;
            npc.tellOwner(source.equals("timeout") ? "没等到答复，我先不这么做。" : "好，那我不这么做。");
            event("owner_refused", "Owner refused " + question.kind() + "; choose another route or report", true);
            return;
        }
        task.grants.add(question.kind());
        task.failedTargets.remove(plan.id());
        npc.tellOwner(source.equals("timeout") ? "没等到答复，我按自己的判断继续。" : "好的，我试试。");
        activeStep = plan.id();
        npc.skills().start(plan, false);
    }

    /** Keywords first; then Jev, when configured, decides whether the reply answers the question or is a new instruction. */
    private void answer(ServerPlayer player, String text) {
        Communicator.Question question = npc.speech().pending().orElseThrow();
        var keyword = Communicator.interpret(text);
        if (keyword.isPresent()) { resolveQuestion(question, keyword.get(), "keyword"); return; }
        NpcConfig config = JevNpcMod.config();
        if (!config.enabled || config.effectiveKey().isBlank()) {
            resolveQuestion(question, "no", "new_instruction");
            startGoal(player, text);
            return;
        }
        var server = npc.getServer();
        JevNpcMod.client().interpretReply(config.effectiveKey(), config.model, question.prompt(), question.options(), text, config.requestTimeoutMs)
            .whenComplete((choice, failure) -> server.execute(() -> {
                if (!server.isSameThread() || npc.speech().pending().orElse(null) != question) return;
                if (failure == null && question.options().containsKey(choice)) resolveQuestion(question, choice, "jev");
                else {
                    resolveQuestion(question, "no", "new_instruction");
                    startGoal(player, text);
                }
            }));
    }

    /** An undirected chat line from the owner counts as a reply only right after the NPC spoke or asked. */
    public boolean expectsReply(ServerPlayer player) {
        return npc.isOwner(player) && player.level() == npc.level() && player.distanceToSqr(npc) <= 16 * 16
            && npc.speech().expectsReply(npc.level().getGameTime());
    }

    private void endGoal(boolean success, String message) {
        if (task != null) {
            lastTask = task.state();
            lastTask.addProperty("outcome", success ? "completed" : "incomplete");
            JevNpcMod.LOGGER.info("Jev goal ended id={} rounds={} outcome={} detail={}", task.id, task.rounds,
                success ? "completed" : "incomplete", message);
        }
        task = null;
        environment = null;
        activeStep = null;
        escalated = null;
        npc.speech().resolve();
        ownerRequest = "";
        gate.invalidate();
        events.drain();
        npc.tellOwner(message);
    }

    public NpcBrain(JevNpcEntity npc) { this.npc = npc; }
    public String status() { return status; }
    public void invalidate() { gate.invalidate(); }
    public void requestHandled() { if (task == null) ownerRequest = ""; }
    public void hold() {
        held = true;
        task = null;
        environment = null;
        activeStep = null;
        escalated = null;
        npc.speech().resolve();
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
        String text = message == null ? "" : message;
        if (text.length() > 300) text = text.substring(0, 300);
        if (!npc.isOwner(player) || player.level() != npc.level() || player.distanceToSqr(npc) > 32 * 32) {
            JevNpcMod.LOGGER.info("Jev owner message ignored npc={} player={} reason={} text=\"{}\"",
                npc.getUUID(), player.getName().getString(), chatIgnoreReason(player), oneLine(text));
            return;
        }
        if (npc.speech().pending().isPresent()) {
            answer(player, text);
            return;
        }
        if (JevNpcMod.config().llmReady()) converse(player, text);
        else startGoal(player, text, null, true);
    }

    private void startGoal(ServerPlayer player, String text) { startGoal(player, text, null, true); }

    /** A known {@code intent} (from conversation) skips Jev's interpretation round. */
    private void startGoal(ServerPlayer player, String text, GoalIntent intent, boolean acknowledge) {
        held = false;
        npc.speech().resolve();
        escalated = null;
        npc.skills().stop();
        task = new AgentTask(text);
        task.intent = intent;
        environment = new EnvironmentTools(npc);
        activeStep = null;
        events.drain();
        nextDecisionTick = 0;
        ownerRequest = text;
        requestAnchor = player.blockPosition();
        npc.remember("Owner said: " + ownerRequest);
        if (intent == null) event("owner_chat", ownerRequest, true);
        else event("goal_interpreted", "Intent set from conversation; choose the next tool for the persistent goal", true);
        JevNpcMod.LOGGER.info("Jev owner message npc={} player={} text=\"{}\" intent={}",
            npc.getUUID(), player.getName().getString(), oneLine(ownerRequest), intent);
        if (acknowledge) npc.tellOwner("已收到指令，我会先判断目标，再观察环境并执行。");
    }

    /**
     * DeepSeek answers in chat and may hand over a goal. Only the newest message's reply is applied; if the call fails
     * the message still reaches the Jev command path.
     */
    private void converse(ServerPlayer player, String text) {
        NpcConfig config = JevNpcMod.config();
        if (!JevNpcMod.llmBudget().acquire(monotonicMs(), config.llmMaxRequestsPerMinute)) {
            JevNpcMod.LOGGER.info("DeepSeek skipped npc={} reason=local_rate_limit", npc.getUUID());
            startGoal(player, text);
            return;
        }
        long turn = ++conversationTurn;
        var messages = conversation.messages(personalityText(), situation(), text);
        var server = npc.getServer();
        JevNpcMod.LOGGER.info("DeepSeek request npc={} model={} turn={} text=\"{}\"", npc.getUUID(), config.llmModel, turn, oneLine(text));
        JevNpcMod.llm().chat(config.effectiveLlmKey(), config.llmModel, messages, config.llmTimeoutMs)
            .whenComplete((reply, failure) -> server.execute(() -> {
                if (!server.isSameThread() || turn != conversationTurn || npc.isRemoved() || !npc.isAlive()) return;
                if (failure != null) {
                    String code = JevClient.errorCode(failure);
                    JevNpcMod.LOGGER.warn("DeepSeek result npc={} turn={} outcome=failed:{}", npc.getUUID(), turn, code);
                    status = "LLM_" + code;
                    startGoal(player, text);
                    return;
                }
                JevNpcMod.LOGGER.info("DeepSeek result npc={} turn={} latencyMs={} promptTokens={} reply=\"{}\" taskRequest=\"{}\" intent={}",
                    npc.getUUID(), turn, reply.elapsedMs(), reply.promptTokens(), oneLine(reply.say()), oneLine(reply.taskRequest()), reply.intent());
                conversation.record(text, reply.say());
                if (!reply.say().isEmpty()) npc.say(reply.say());
                if (!reply.hasTask()) {
                    npc.remember("Chatted with owner: " + text);
                    return;
                }
                startGoal(player, reply.intent() != null || reply.taskRequest().isEmpty() ? text : reply.taskRequest(), reply.intent(), reply.say().isEmpty());
            }));
    }

    private String personalityText() {
        return switch (npc.personality()) {
            case "brave" -> "勇敢，保护主人，愿意和敌对生物战斗，说到做到";
            case "diligent" -> "勤快，做事有始有终，不喜欢被无故打断，也注意安全";
            default -> "谨慎友善，看重安全和承诺，打不过时会求助或撤退";
        };
    }

    /** What the conversation model may know: observed facts only, no coordinates of other players. */
    private JsonObject situation() {
        JsonObject situation = new JsonObject();
        situation.addProperty("health", Math.round(npc.getHealth()) + "/" + Math.round(npc.getMaxHealth()));
        situation.addProperty("time", npc.level().isNight() ? "night" : "day");
        situation.addProperty("weather", weather == null ? "unknown" : weather);
        situation.addProperty("current_activity", npc.skills().summary());
        situation.addProperty("current_goal", task == null ? "none" : task.request + (task.intent == null ? "" : " " + task.intent));
        ServerPlayer owner = npc.owner();
        if (owner != null) situation.addProperty("owner_distance_blocks", Math.round(npc.distanceTo(owner)));
        situation.addProperty("home_distance_blocks", Math.round(Math.sqrt(npc.distanceToSqr(Vec3.atCenterOf(npc.home())))));
        JsonArray backpack = new JsonArray();
        for (ItemStack stack : npc.backpack().getItems())
            if (!stack.isEmpty()) backpack.add(stack.getHoverName().getString() + "×" + stack.getCount());
        situation.add("backpack", backpack);
        JsonArray memories = new JsonArray();
        npc.memories().forEach(memories::add);
        situation.add("recent_memory", memories);
        situation.add("own_needs", Drives.json(needs, npc.personality()));
        return situation;
    }

    public void tick() {
        if (held) return;
        NpcConfig config = JevNpcMod.config();
        ServerPlayer owner = npc.owner();
        if (owner == null || owner.level() != npc.level() || owner.distanceToSqr(npc) > 48 * 48) return;
        long tick = npc.tickCount;
        if (tick % 40 == 0) notice();
        Communicator.Question waiting = npc.speech().pending().orElse(null);
        if (waiting != null) {
            npc.speech().expire(npc.level().getGameTime()).ifPresent(fallback -> resolveQuestion(waiting, fallback, "timeout"));
            return;
        }
        if (task != null) {
            task.elapsedTicks++;
            // A last allowed tool may still finish; round exhaustion is checked before the next HTTP call.
            if (task.elapsedTicks >= config.maxGoalTicks || task.failures >= 4) {
                npc.skills().stop();
                endGoal(false, "这次任务未能完成，已达到时间或失败次数上限。");
                return;
            }
            if (npc.skills().emergencyLocked() || activeStep != null && npc.skills().hasTask()) return;
            if (activeStep != null && npc.skills().hasSuspendedTask()) {
                npc.skills().resume();
                return;
            }
            if (activeStep != null) {
                activeStep = null;
                event("tool_interrupted", "Previous tool was interrupted; reassess the goal", true);
            }
            if (!gate.inFlight() && task.exhausted(config.maxGoalRounds, config.maxGoalTicks)) {
                endGoal(task.canComplete(), task.canComplete() ? "任务已完成。" : "这次任务未能完成，已达到决策轮数上限。");
                return;
            }
        }
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
        boolean idle = task == null && !npc.skills().hasTask();
        if (!idle) idleSince = tick;
        if (idle && config.autonomyEnabled && tick - lastAutonomyTick >= config.autonomyIdleTicks) {
            event("idle", "No owner goal and nothing running; consider the NPC's own needs in `drives`", false);
            lastAutonomyTick = tick;
        }
        if (!events.ready(tick, config.eventDebounceTicks) || gate.inFlight() || tick < nextDecisionTick) return;
        if (task == null) needs = config.autonomyEnabled ? Drives.needs(driveState()).stream()
            .filter(need -> initiativeCooldowns.getOrDefault(need.action(), 0L) <= tick).toList() : List.of();
        if (!config.enabled || config.effectiveKey().isBlank()) {
            status = config.enabled ? "NO_KEY: 本地技能模式" : "DISABLED: 本地技能模式";
            Map<String, String> skipped = events.drain();
            JevNpcMod.LOGGER.info("Jev request skipped npc={} reason={} ownerRequest=\"{}\" events=[{}]",
                npc.getUUID(), status, oneLine(ownerRequest), formatEvents(skipped));
            if (idle && config.autonomyEnabled) chooseLocally();
            else if (task != null && task.intent != null && activeStep == null && !npc.skills().hasTask()) chooseToolLocally();
            return;
        }
        long nowMs = monotonicMs();
        if (!JevNpcMod.budget().acquire(nowMs, config.maxRequestsPerMinute)) {
            status = "LOCAL_RATE_LIMIT: 等待预算";
            nextDecisionTick = tick + 20;
            return;
        }
        Map<String, ActionPlan> options = options();
        if (task != null) task.rounds++;
        List<Candidate> candidates = options.values().stream().map(ActionPlan::candidate).toList();
        Map<String, String> triggeringEvents = events.drain();
        JsonObject state = snapshot(triggeringEvents);
        String requestText = oneLine(ownerRequest);
        String eventText = formatEvents(triggeringEvents);
        String candidateText = candidates.stream().map(Candidate::id).reduce((left, right) -> left + "," + right).orElse("(none)");
        DecisionGate.Ticket ticket = gate.begin(nowMs).orElseThrow();
        nextDecisionTick = tick + config.decisionCooldownTicks;
        status = "REQUESTING";
        if (task != null) JevNpcMod.LOGGER.info("Jev goal request id={} round={} state={}", task.id, task.rounds, state);
        JevNpcMod.LOGGER.info("Jev request npc={} model={} ownerRequest=\"{}\" events=[{}] candidates=[{}]",
            npc.getUUID(), config.model, requestText, eventText, candidateText);
        var server = npc.getServer();
        var world = npc.level();
        JevNpcMod.client().decide(config.effectiveKey(), config.model, state, candidates, config.requestTimeoutMs)
            .whenComplete((decision, failure) -> server.execute(() -> {
                // A stopping server runs submitted tasks inline on the HTTP thread; world state must not be touched there.
                if (!server.isSameThread()) return;
                boolean valid = gate.complete(ticket, monotonicMs(), config.maxResultAgeMs);
                if (held) {
                    status = "PAUSED: 手动控制";
                    logResult(requestText, eventText, "discarded:paused");
                    return;
                }
                if (!valid || npc.isRemoved() || !npc.isAlive() || npc.level() != world) {
                    status = "STALE: 丢弃旧判断";
                    logResult(requestText, eventText, "discarded:stale");
                    if (task != null) event("retry_stale", "Refresh stale decision", false);
                    return;
                }
                ServerPlayer currentOwner = npc.owner();
                if (currentOwner == null || currentOwner.level() != world || currentOwner.distanceToSqr(npc) > 48 * 48) {
                    status = "OWNER_UNAVAILABLE";
                    logResult(requestText, eventText, "discarded:owner_unavailable");
                    return;
                }
                if (failure != null) {
                    status = JevClient.errorCode(failure);
                    nextDecisionTick = npc.tickCount + (status.equals("HTTP_401") || status.equals("HTTP_403") ? 1200 : 200);
                    npc.tellOwner("Jev 未完成判断（" + status + "），继续本地行为。/jev status 可查看状态。");
                    JevNpcMod.LOGGER.warn("Jev result npc={} ownerRequest=\"{}\" outcome=failed:{}",
                        npc.getUUID(), requestText, status);
                    if (task != null) {
                        task.feedback("model_request", false, 0, status);
                        if (status.equals("HTTP_401") || status.equals("HTTP_403")) endGoal(false, "模型认证失败，任务已停止，请检查配置。");
                        else event("retry_request", "Retry model request after backoff", false);
                    }
                    return;
                }
                status = decision.candidateId() + " | " + decision.elapsedMs() + "ms | confidence="
                    + String.format(java.util.Locale.ROOT, "%.2f", decision.confidence()) + " | tokens=" + decision.inputTokens();
                boolean keep = decision.confidence() < config.minimumConfidence || npc.skills().emergencyLocked();
                String outcome = !keep ? "applied"
                    : npc.skills().emergencyLocked() ? "kept:emergency"
                    : "kept:confidence_below_" + config.minimumConfidence;
                JevNpcMod.LOGGER.info("Jev result npc={} ownerRequest=\"{}\" decision={} outcome={} model={} latencyMs={} inputTokens={} confidence={}",
                    npc.getUUID(), requestText, decision.candidateId(), outcome, decision.model(),
                    decision.elapsedMs(), decision.inputTokens(), decision.confidence());
                if (decision.intent() != null) JevNpcMod.LOGGER.info("Jev interpretation npc={} intent={} judgments={}",
                    npc.getUUID(), decision.intent(), decision.diagnostics());
                if (keep) {
                    status += " | kept current task";
                    if (task != null && decision.intent() != null && !npc.skills().emergencyLocked()) {
                        endGoal(false, "我还没确定这条指令的动作或目标，请补充一下要做什么、针对什么。");
                        return;
                    }
                    if (task != null) {
                        task.feedback("decision", false, 0, "uncertain or emergency");
                        event("retry_decision", "Reconsider uncertain decision using the observed evidence", false);
                    }
                    return;
                }
                if (task != null && decision.intent() != null) {
                    task.intent = decision.intent();
                    if (task.intent.verb().equals("unsupported")) {
                        endGoal(false, "这条指令超出了当前能力，或目标还不明确。可以让我去附近浅水、采集木头、挖地面、攻击指定生物、跟随或守卫。");
                    } else event("goal_interpreted", "Choose the next tool for the persistent goal", false);
                    return;
                }
                ActionPlan plan = options.get(decision.candidateId());
                if (plan == null) return;
                if (task != null) {
                    applyTool(plan);
                    return;
                }
                boolean interrupt = plan.skill() == Skill.ATTACK || plan.skill() == Skill.FLEE;
                npc.skills().start(plan, interrupt);
            }));
    }

    private void applyTool(ActionPlan plan) {
        switch (plan.skill()) {
            case OBSERVE -> {
                environment.observe(task, requestAnchor == null ? npc.blockPosition() : requestAnchor);
                JevNpcMod.LOGGER.info("Jev observation goal={} round={} result={}", task.id, task.rounds, environment.state());
                if (environment.state().getAsJsonArray("targets").isEmpty()) npc.tellOwner(environment.unavailableMessage(task.gathered > 0));
                event("observation_ready", "Search results available; choose the next tool", false);
            }
            case FINISH -> {
                if (task.canComplete()) endGoal(true, "任务已完成。");
                else event("completion_rejected", "Goal completion is not verified", false);
            }
            case REPORT -> endGoal(false, environment.unavailableMessage(task.gathered > 0));
            default -> {
                activeStep = plan.id();
                npc.skills().start(plan, false);
                if (plan.skill() == Skill.FOLLOW || plan.skill() == Skill.WAIT || plan.skill() == Skill.GUARD) {
                    task.actionSucceeded = true;
                    endGoal(true, "已进入指定行为，会持续执行，直到你给出新指令。");
                }
            }
        }
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
        if (task != null) tag.putString("agentTask", task.save());
        if (activeStep != null) tag.putString("activeStep", activeStep);
        if (requestAnchor != null) tag.putLong("requestAnchor", requestAnchor.asLong());
        return tag;
    }

    public void load(CompoundTag tag) {
        held = tag.getBoolean("held");
        String savedRequest = tag.getString("ownerRequest");
        ownerRequest = savedRequest.length() > 300 ? savedRequest.substring(0, 300) : savedRequest;
        requestAnchor = tag.contains("requestAnchor") ? BlockPos.of(tag.getLong("requestAnchor")) : null;
        if (!held && tag.contains("agentTask")) {
            try {
                task = AgentTask.load(tag.getString("agentTask"));
                environment = new EnvironmentTools(npc);
                activeStep = tag.contains("activeStep") ? tag.getString("activeStep") : null;
                event("goal_loaded", "Resume saved goal; observations must be refreshed", false);
            } catch (RuntimeException error) { task = null; activeStep = null; ownerRequest = ""; }
        }
        status = held ? "PAUSED: 手动控制" : "loaded; awaiting event";
    }

    public Map<String, ActionPlan> options() {
        if (task != null) return environment.options(task, requestAnchor == null ? npc.blockPosition() : requestAnchor);
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
        // The NPC's own initiatives exist only for measured needs, and gathering only where autonomy may change the world.
        for (Drives.Need need : needs) {
            String why = " Serves the NPC's own need: " + need.reason() + ".";
            switch (need.action()) {
                case "stock_blocks" -> add(result, "stock_blocks", Skill.MINE, npc.blockPosition(), null, "blocks", 8,
                    "Dig 8 natural dirt or stone blocks nearby to keep building blocks for bridging and pillaring." + why);
                case "stock_wood" -> add(result, "stock_wood", Skill.HARVEST, npc.blockPosition(), null, "", 4,
                    "Collect 4 logs from a natural tree nearby to keep for later." + why);
                case "rejoin_owner" -> {
                    ServerPlayer owner = npc.owner();
                    if (owner != null) add(result, "rejoin_owner", Skill.MOVE, owner.blockPosition(), null, "", 1,
                        "Walk back to where the owner is now, then stay free for other activities." + why);
                }
                case "wander" -> {
                    Vec3 spot = LandRandomPos.getPos(npc, 6, 3);
                    if (spot != null) add(result, "wander", Skill.MOVE, BlockPos.containing(spot), null, "", 1,
                        "Stroll to a nearby spot and look around." + why);
                }
                default -> {}
            }
        }
        return result;
    }

    private Drives.State driveState() {
        NpcConfig config = JevNpcMod.config();
        ServerPlayer owner = npc.owner();
        double homeDistance = Math.sqrt(npc.distanceToSqr(Vec3.atCenterOf(npc.home())));
        boolean mayGather = config.autonomyMayModifyWorld && config.allowBlockChanges && homeDistance <= config.autonomyHomeRadius;
        int logs = 0;
        for (ItemStack stack : npc.backpack().getItems()) if (stack.is(ItemTags.LOGS)) logs += stack.getCount();
        return new Drives.State(npc.getHealth(), npc.getMaxHealth(), npc.backpack().countItem(Items.BREAD),
            npc.skills().navigator().carriedBlocks(), logs, npc.level().isNight(), homeDistance,
            owner == null ? 0 : npc.distanceTo(owner), npc.skills().nearestEnemy(16) != null, npc.tickCount - idleSince,
            npc.personality(), mayGather,
            mayGather && npc.skills().findWorkBlock(npc.blockPosition(), "blocks").isPresent(),
            mayGather && npc.skills().findWorkBlock(npc.blockPosition(), "log").isPresent());
    }

    /** Without Jev, a goal whose intent is already known still advances by a fixed preference over the bound tools. */
    private void chooseToolLocally() {
        Map<String, ActionPlan> options = options();
        List<ActionPlan> plans = List.copyOf(options.values());
        ActionPlan plan = first(plans, Skill.FINISH)
            .or(() -> task.intent.gathering() && task.gathered >= task.intent.amount() ? first(plans, Skill.GIVE) : Optional.empty())
            .or(() -> plans.stream().filter(p -> p.skill() != Skill.OBSERVE && p.skill() != Skill.REPORT && p.skill() != Skill.GIVE).findFirst())
            .or(() -> first(plans, Skill.OBSERVE))
            .or(() -> first(plans, Skill.GIVE))
            .orElse(options.get("cannot_complete"));
        if (plan == null) return;
        task.rounds++;
        JevNpcMod.LOGGER.info("Jev goal local tool goal={} round={} tool={}", task.id, task.rounds, plan.id());
        applyTool(plan);
    }

    private static Optional<ActionPlan> first(List<ActionPlan> plans, Skill skill) {
        return plans.stream().filter(plan -> plan.skill() == skill).findFirst();
    }

    /** Without a model the most pressing weighted need wins, so autonomy never depends on a paid call. */
    private void chooseLocally() {
        var need = Drives.choose(needs, npc.personality());
        if (need.isEmpty()) return;
        String action = need.get().action();
        ActionPlan plan = options().get(action);
        if (plan == null) return;
        JevNpcMod.LOGGER.info("Jev autonomy npc={} source=local need={} action={} urgency={}", npc.getUUID(), need.get().id(),
            action, String.format(java.util.Locale.ROOT, "%.2f", Drives.weighted(need.get(), npc.personality())));
        status = "AUTONOMY(local): " + action;
        npc.skills().start(plan, plan.skill() == Skill.ATTACK || plan.skill() == Skill.FLEE);
    }

    /** Things a player would mention unprompted. The communicator drops repeats and keeps remarks spaced out. */
    private void notice() {
        Communicator speech = npc.speech();
        var level = npc.level();
        long now = level.getGameTime();
        long time = level.getDayTime() % 24000;
        if (time >= 12000 && time < 12600 && level.canSeeSky(npc.blockPosition())) {
            boolean farFromHome = npc.distanceToSqr(Vec3.atCenterOf(npc.home())) > 32 * 32;
            speech.remark("nightfall", farFromHome ? "天快黑了，我们离家有点远，小心怪物。" : "天快黑了，怪物要出来了。", 12000, now);
        }
        if (npc.getHealth() <= 12 && !npc.skills().emergencyLocked())
            speech.remark("hurt", "我受伤了，血量只剩 " + Math.round(npc.getHealth()) + "。", 1200, now);
        int free = 0;
        for (ItemStack stack : npc.backpack().getItems()) {
            if (stack.isEmpty()) free++;
            else if (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() * 0.85)
                speech.remark("tool:" + BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getHoverName().getString() + "快用坏了。", 6000, now);
        }
        ItemStack held = npc.getMainHandItem();
        if (held.isDamageableItem() && held.getDamageValue() >= held.getMaxDamage() * 0.85)
            speech.remark("tool:" + BuiltInRegistries.ITEM.getKey(held.getItem()), held.getHoverName().getString() + "快用坏了。", 6000, now);
        if (free <= 3) speech.remark("backpack", "背包快满了，只剩 " + free + " 格。", 6000, now);
        if (npc.tickCount % 200 == 0) {
            BlockPos center = npc.blockPosition();
            for (BlockPos cursor : BlockPos.betweenClosed(center.offset(-6, -4, -6), center.offset(6, 4, 6))) {
                if (!level.hasChunkAt(cursor)) continue;
                BlockState state = level.getBlockState(cursor);
                if (!state.is(BlockTags.DIAMOND_ORES) && !state.is(BlockTags.EMERALD_ORES) || !exposed(cursor)) continue;
                speech.remark("ore:" + cursor.asLong(), "我在 " + cursor.toShortString() + " 附近看到了" + state.getBlock().getName().getString() + "。",
                    Integer.MAX_VALUE, now);
                break;
            }
        }
    }

    private boolean exposed(BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.relative(direction);
            if (npc.level().hasChunkAt(neighbor) && npc.level().getBlockState(neighbor).isAir()) return true;
        }
        return false;
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
        if (task != null) {
            state.add("task", task.state());
            state.add("environment", environment.state());
        } else state.add("drives", Drives.json(needs, npc.personality()));
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
            + "Long tasks run locally. Weather alone need not interrupt work. "
            + (task != null ? "`task` is the owner's current request, issued just now; an identical earlier request that was "
                + "completed does not complete this one." : "Do not repeat a completed owner request."));
        return state;
    }

    private String chatIgnoreReason(ServerPlayer player) {
        if (!npc.isOwner(player)) return "not_owner";
        if (player.level() != npc.level()) return "different_dimension";
        return "out_of_range";
    }

    private void logResult(String requestText, String eventText, String outcome) {
        JevNpcMod.LOGGER.info("Jev result npc={} ownerRequest=\"{}\" events=[{}] outcome={}",
            npc.getUUID(), requestText, eventText, outcome);
    }

    private static String oneLine(String text) {
        if (text == null || text.isBlank()) return "(none)";
        return text.replace('\r', ' ').replace('\n', ' ').replace('"', '\'');
    }

    private static String formatEvents(Map<String, String> events) {
        if (events.isEmpty()) return "(none)";
        StringBuilder builder = new StringBuilder();
        events.forEach((kind, detail) -> {
            if (!builder.isEmpty()) builder.append("; ");
            builder.append(kind).append('=').append(oneLine(detail));
        });
        return builder.toString();
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
