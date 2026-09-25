package dev.jev.npc.ai;

import java.util.Optional;
import java.util.Set;

/** One lowered executable method inside a goal stage; never the whitelist of high-level goals. */
public record GoalIntent(String verb, String material, String place, int amount, boolean deliverToOwner) {
    /** Real method names from the shared catalog. */
    public static final Set<String> TASK_VERBS = ToolCatalog.names();

    public boolean gathering() { return verb.equals("harvest") || verb.equals("mine"); }

    /**
     * Accepts an intent proposed by a model only when every field that matters for its verb is a supported value.
     * {@code amount == 0} means unspecified and takes the demo default (four logs, one terrain block).
     */
    public static Optional<GoalIntent> validated(String verb, String material, String place, int amount, boolean deliver) {
        if (verb == null || !TASK_VERBS.contains(verb) || amount < 0) return Optional.empty();
        if (verb.equals("harvest") && material != null && !material.isBlank() && !material.equals("log")) return Optional.empty();
        String mined = verb.equals("mine") ? material : "log";
        if (verb.equals("mine") && !(material != null && Set.of("ground", "stone").contains(material))) return Optional.empty();
        String destination = verb.equals("go_to") ? place : "owner";
        if (verb.equals("go_to") && !(place != null && Set.of("water", "home", "owner").contains(place))) return Optional.empty();
        boolean gathering = verb.equals("harvest") || verb.equals("mine");
        int count = !gathering ? 1 : amount == 0 ? (verb.equals("mine") ? 1 : 4) : amount;
        if (count < 1 || count > 256) return Optional.empty();
        return Optional.of(new GoalIntent(verb, mined, destination, count, gathering && deliver));
    }
}
