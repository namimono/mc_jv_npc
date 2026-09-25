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
        if (intent == null) return false;
        if (intent.gathering()) return gathered >= intent.amount() && (!intent.deliverToOwner() || delivered);
        return actionSucceeded;
    }

    public JsonObject state() {
        JsonObject state = new JsonObject();
        state.addProperty("id", id);
        state.addProperty("request", request);
        state.add("intent", GSON.toJsonTree(intent));
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
        return task;
    }
}
