package dev.jev.npc.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One owner's goal, surviving tool completions. No world access or HTTP here. */
public final class AgentTask {
    private static final Gson GSON = new Gson();
    public final String id;
    public String request;
    public GoalIntent intent;
    public GoalPlan plan;
    public int stageIndex;
    public long planRevision;
    public Map<Integer, StageProgress> stages = new LinkedHashMap<>();
    public static final class StageProgress {
        int gathered;
        int deliveredCount;
        boolean actionSucceeded, delivered;
        Map<String, Integer> collected = new LinkedHashMap<>();
        Set<String> failedTargets = new LinkedHashSet<>();
    }

    public void setPlan(GoalPlan plan) {
        if (plan.stages().stream().anyMatch(stage -> stage.fromStage() != null))
            throw new IllegalArgumentException("A new goal cannot continue previous stages");
        this.plan = plan;
        planRevision++;
        intent = plan.stages().getFirst().method();
    }

    /** Validate first so a malformed proposal cannot stop work or discard its evidence. */
    public void validateAmendment(GoalPlan proposal) {
        Set<Integer> retained = new LinkedHashSet<>();
        for (GoalPlan.Stage stage : proposal.stages()) {
            Integer source = stage.fromStage();
            if (source == null) continue;
            int size = plan == null ? 1 : plan.stages().size();
            if (source < 0 || source >= size || !retained.add(source))
                throw new IllegalArgumentException("Unknown or repeated continuation stage");
            GoalIntent old = plan == null ? intent : plan.stages().get(source).method();
            GoalIntent next = stage.method();
            if (old == null || !old.verb().equals(next.verb()) || !old.material().equals(next.material())
                || !old.place().equals(next.place()))
                throw new IllegalArgumentException("Continuation changes the executed method or target");
        }
        if (retained.isEmpty()) throw new IllegalArgumentException("Amendment must identify retained stages");
    }

    /** Amounts are revised cumulative totals, including work already gathered or delivered. */
    public void amend(String request, GoalPlan proposal, int unfinishedGathered) {
        validateAmendment(proposal);
        if (unfinishedGathered < 0 || unfinishedGathered > 0 && !intent.gathering())
            throw new IllegalArgumentException("Invalid unfinished progress");
        gathered += unfinishedGathered;
        captureStage();
        Map<Integer, StageProgress> retained = new LinkedHashMap<>();
        int selected = 0;
        for (int i = 0; i < proposal.stages().size(); i++) {
            Integer source = proposal.stages().get(i).fromStage();
            if (source == null) continue;
            if (source == stageIndex) selected = i;
            if (stages.containsKey(source)) {
                StageProgress progress = stages.get(source);
                if (progress.delivered && progress.deliveredCount == 0) progress.deliveredCount = progress.gathered;
                progress.delivered = progress.deliveredCount >= proposal.stages().get(i).method().amount();
                retained.put(i, progress);
            }
        }
        this.request = request;
        plan = proposal.adopted();
        planRevision++;
        stages = retained;
        restoreStage(selected);
        feedback("amend_goal", true, unfinishedGathered, "Retained referenced stages; amounts are cumulative totals");
    }

    private void captureStage() {
        StageProgress progress = new StageProgress();
        progress.gathered = gathered;
        progress.deliveredCount = deliveredAmount();
        progress.actionSucceeded = actionSucceeded;
        progress.delivered = delivered;
        progress.collected.putAll(collected);
        progress.failedTargets.addAll(failedTargets);
        stages.put(stageIndex, progress);
    }

    public boolean stageComplete() {
        return complete(intent, gathered, delivered, actionSucceeded);
    }

    public boolean stageComplete(int index) {
        if (index == stageIndex) return stageComplete();
        StageProgress progress = stages.get(index);
        return progress != null && complete(plan.stages().get(index).method(), progress.gathered,
            progress.delivered, progress.actionSucceeded);
    }

    private static boolean complete(GoalIntent method, int gathered, boolean delivered, boolean succeeded) {
        if (method == null) return false;
        return method.gathering() ? gathered >= method.amount() && (!method.deliverToOwner() || delivered) : succeeded;
    }

    /** Jev chooses a stage; counters and delivery provenance survive switching and saving. */
    public void selectStage(int index) {
        if (plan == null || index < 0 || index >= plan.stages().size()) throw new IllegalArgumentException("Invalid stage");
        captureStage();
        revision++;
        restoreStage(index);
    }

    private void restoreStage(int index) {
        stageIndex = index;
        intent = plan.stages().get(index).method();
        StageProgress progress = stages.getOrDefault(index, new StageProgress());
        gathered = progress.gathered;
        deliveredCount = progress.deliveredCount;
        actionSucceeded = progress.actionSucceeded;
        delivered = progress.delivered;
        collected.clear(); collected.putAll(progress.collected);
        failedTargets.clear(); failedTargets.addAll(progress.failedTargets);
    }
    public long revision;
    public int rounds;
    public int elapsedTicks;
    public int failures;
    public int gathered;
    public int deliveredCount;
    public boolean actionSucceeded;
    public boolean delivered;
    public final Map<String, Integer> collected = new LinkedHashMap<>();
    public final Set<String> failedTargets = new LinkedHashSet<>();
    public final List<JsonObject> history = new ArrayList<>();
    /** Permissions the owner granted for this goal only: {@code risk}, {@code break_built}. */
    public Set<String> grants = new LinkedHashSet<>();

    public AgentTask(String request) {
        this.id = java.util.UUID.randomUUID().toString();
        this.request = request;
    }

    /** Older saves recorded only a fully delivered flag; their gathered amount is the delivery evidence. */
    public int deliveredAmount() { return deliveredCount == 0 && delivered ? gathered : deliveredCount; }
    public int deliveryRemaining() { return intent == null || !intent.deliverToOwner() ? 0 : Math.max(0, intent.amount() - deliveredAmount()); }

    /** Give only the outstanding cumulative quota. Excess stays in the backpack and its provenance is retained. */
    public Map<String, Integer> deliverableItems() {
        Map<String, Integer> items = new LinkedHashMap<>();
        int remaining = deliveryRemaining();
        for (var item : collected.entrySet()) {
            int count = Math.min(remaining, item.getValue());
            if (count > 0) items.put(item.getKey(), count);
            remaining -= count;
        }
        return items;
    }

    public void feedback(String tool, boolean success, int progress, String detail) {
        revision++;
        JsonObject result = new JsonObject();
        result.addProperty("tool", tool);
        result.addProperty("success", success);
        result.addProperty("progress", progress);
        result.addProperty("detail", detail);
        history.add(result);
        if (history.size() > 12) history.removeFirst();
        if (progress > 0) failures = 0;
        else if (!success) failures++;
    }

    public boolean exhausted(int maxRounds, int maxTicks) {
        return rounds >= maxRounds || elapsedTicks >= maxTicks || failures >= 4;
    }

    public boolean canComplete() {
        if (plan == null) return stageComplete();
        for (int i = 0; i < plan.stages().size(); i++) if (!stageComplete(i)) return false;
        return true;
    }

    public JsonObject state() {
        JsonObject state = new JsonObject();
        state.addProperty("id", id);
        state.addProperty("request", request);
        state.add("intent", GSON.toJsonTree(intent));
        if (plan != null) {
            state.add("plan", plan.json());
            state.addProperty("current_stage", stageIndex);
            JsonArray completed = new JsonArray();
            for (int i = 0; i < plan.stages().size(); i++) if (stageComplete(i)) completed.add(i);
            state.add("verified_stages", completed);
            JsonArray progress = new JsonArray();
            for (int i = 0; i < plan.stages().size(); i++) {
                StageProgress stored = stages.getOrDefault(i, new StageProgress());
                JsonObject item = new JsonObject();
                item.addProperty("stage", i);
                item.addProperty("gathered", i == stageIndex ? gathered : stored.gathered);
                item.addProperty("delivered", i == stageIndex ? delivered : stored.delivered);
                item.addProperty("delivered_count", i == stageIndex ? deliveredAmount()
                    : stored.deliveredCount == 0 && stored.delivered ? stored.gathered : stored.deliveredCount);
                item.add("collected", GSON.toJsonTree(i == stageIndex ? collected : stored.collected));
                progress.add(item);
            }
            state.add("stage_progress", progress);
        }
        state.addProperty("stage_completion_verified", stageComplete());
        state.addProperty("round", rounds);
        state.addProperty("gathered_blocks", gathered);
        state.addProperty("delivered", delivered);
        state.addProperty("delivered_count", deliveredAmount());
        state.addProperty("completion_verified", canComplete());
        state.add("collected_items", GSON.toJsonTree(collected));
        state.add("failed_targets", GSON.toJsonTree(failedTargets));
        state.add("owner_grants", GSON.toJsonTree(grants));
        JsonArray results = new JsonArray();
        history.forEach(item -> results.add(item.deepCopy()));
        state.add("tool_results", results);
        return state;
    }

    public String save() { return GSON.toJson(this); }
    public static AgentTask load(String json) {
        AgentTask task = GSON.fromJson(json, AgentTask.class);
        if (task == null || task.id == null || task.request == null || task.history == null
            || task.collected == null || task.failedTargets == null) throw new IllegalArgumentException("Invalid task");
        if (task.grants == null) task.grants = new LinkedHashSet<>();
        if (task.stages == null) task.stages = new LinkedHashMap<>();
        task.deliveredCount = task.deliveredAmount();
        for (StageProgress progress : task.stages.values())
            if (progress.delivered && progress.deliveredCount == 0) progress.deliveredCount = progress.gathered;
        if (task.plan != null && (task.stageIndex < 0 || task.stageIndex >= task.plan.stages().size()))
            throw new IllegalArgumentException("Invalid saved stage");
        return task;
    }
}
