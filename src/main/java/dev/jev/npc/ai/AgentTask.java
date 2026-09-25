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
    public final String request;
    public GoalIntent intent;
    public GoalPlan plan;
    public int stageIndex;
    public Map<Integer, StageProgress> stages = new LinkedHashMap<>();
    public static final class StageProgress {
        int gathered;
        boolean actionSucceeded, delivered;
        Map<String, Integer> collected = new LinkedHashMap<>();
        Set<String> failedTargets = new LinkedHashSet<>();
    }

    public void setPlan(GoalPlan plan) {
        this.plan = plan;
        intent = plan.stages().getFirst().method();
    }

    private void captureStage() {
        StageProgress progress = new StageProgress();
        progress.gathered = gathered;
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
        stageIndex = index;
        intent = plan.stages().get(index).method();
        StageProgress progress = stages.getOrDefault(index, new StageProgress());
        gathered = progress.gathered;
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
                item.add("collected", GSON.toJsonTree(i == stageIndex ? collected : stored.collected));
                progress.add(item);
            }
            state.add("stage_progress", progress);
        }
        state.addProperty("stage_completion_verified", stageComplete());
        state.addProperty("round", rounds);
        state.addProperty("gathered_blocks", gathered);
        state.addProperty("delivered", delivered);
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
        if (task.plan != null && (task.stageIndex < 0 || task.stageIndex >= task.plan.stages().size()))
            throw new IllegalArgumentException("Invalid saved stage");
        return task;
    }
}
