package dev.jev.npc.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** A free-form objective composed from actual methods; method schemas are not a whitelist of goals. */
public record GoalPlan(String objective, List<String> constraints, String completion, List<Stage> stages) {
    public record Stage(String purpose, GoalIntent method) {}
    public GoalPlan {
        if (objective == null || objective.isBlank() || completion == null || completion.isBlank()
            || stages == null || stages.isEmpty() || stages.size() > 8) throw new IllegalArgumentException("Invalid goal plan");
        if (constraints == null || constraints.size() > 8) throw new IllegalArgumentException("Invalid constraints");
        constraints = List.copyOf(constraints);
        stages = List.copyOf(stages);
        if (stages.stream().anyMatch(s -> s == null || s.method() == null || s.purpose() == null || s.purpose().isBlank()))
            throw new IllegalArgumentException("Missing method/purpose");
        for (int i = 0; i < stages.size() - 1; i++)
            if (java.util.Set.of("follow", "wait", "guard").contains(stages.get(i).method().verb()))
                throw new IllegalArgumentException("Persistent behavior must be the final stage");
    }
    public JsonObject json() {
        JsonObject result = new JsonObject();
        result.addProperty("objective", objective);
        result.add("constraints", new Gson().toJsonTree(constraints));
        result.addProperty("completion", completion);
        JsonArray steps = new JsonArray();
        for (Stage stage : stages) {
            JsonObject step = new JsonObject();
            step.addProperty("purpose", stage.purpose());
            step.addProperty("tool", stage.method().verb());
            step.addProperty("material", stage.method().material());
            step.addProperty("place", stage.method().place());
            step.addProperty("amount", stage.method().amount());
            step.addProperty("deliver_to_owner", stage.method().deliverToOwner());
            steps.add(step);
        }
        result.add("stages", steps);
        return result;
    }
    public static GoalPlan parse(JsonObject value) {
        List<String> constraints = new ArrayList<>();
        if (value.has("constraints")) for (JsonElement item : value.getAsJsonArray("constraints")) {
            if (constraints.size() >= 8) throw new IllegalArgumentException("Too many constraints");
            constraints.add(DeepSeekClient.clean(item.getAsString(), 200));
        }
        List<Stage> stages = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray("stages")) {
            JsonObject stage = item.getAsJsonObject();
            if (stage.has("amount") && (!stage.get("amount").isJsonPrimitive() || !stage.getAsJsonPrimitive("amount").isNumber()))
                throw new IllegalArgumentException("Amount must be a number");
            if (stage.has("deliver_to_owner") && (!stage.get("deliver_to_owner").isJsonPrimitive()
                || !stage.getAsJsonPrimitive("deliver_to_owner").isBoolean())) throw new IllegalArgumentException("Delivery must be boolean");
            int amount = stage.has("amount") ? stage.get("amount").getAsBigDecimal().intValueExact() : 0;
            GoalIntent method = GoalIntent.validated(text(stage, "tool"), text(stage, "material"), text(stage, "place"), amount,
                stage.has("deliver_to_owner") && stage.get("deliver_to_owner").getAsBoolean()).orElseThrow();
            stages.add(new Stage(text(stage, "purpose"), method));
        }
        return new GoalPlan(text(value, "objective"), constraints, text(value, "completion"), stages);
    }
    private static String text(JsonObject value, String key) {
        return value.has(key) && !value.get(key).isJsonNull() ? DeepSeekClient.clean(value.get(key).getAsString(), 300) : "";
    }
}
