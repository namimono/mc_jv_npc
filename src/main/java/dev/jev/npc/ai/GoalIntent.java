package dev.jev.npc.ai;

import java.util.Optional;
import java.util.Set;

/** Typed semantic interpretation; coordinates and entity identities remain code-owned. */
public record GoalIntent(String verb, String material, String place, int amount, boolean deliverToOwner) {
    /** Goals a conversation model may hand over; speaking is its own job, so {@code speak} is not among them. */
    public static final Set<String> TASK_VERBS = Set.of("attack", "harvest", "mine", "go_to", "follow", "guard", "wait", "equip", "eat", "build");

    public boolean gathering() { return verb.equals("harvest") || verb.equals("mine"); }

    /**
     * Accepts an intent proposed by a model only when every field that matters for its verb is a supported value.
     * {@code amount <= 0} means unspecified and takes the demo default (four logs, one terrain block).
     */
    public static Optional<GoalIntent> validated(String verb, String material, String place, int amount, boolean deliver) {
        if (verb == null || !TASK_VERBS.contains(verb)) return Optional.empty();
        String mined = verb.equals("mine") ? material : "log";
        if (verb.equals("mine") && !Set.of("ground", "stone").contains(material)) return Optional.empty();
        String destination = verb.equals("go_to") ? place : "owner";
        if (verb.equals("go_to") && !Set.of("water", "home", "owner").contains(place)) return Optional.empty();
        boolean gathering = verb.equals("harvest") || verb.equals("mine");
        int count = !gathering ? 1 : amount <= 0 ? (verb.equals("mine") ? 1 : 4) : amount;
        if (count != 1 && count != 4) return Optional.empty();
        return Optional.of(new GoalIntent(verb, mined, destination, count, gathering && deliver));
    }
}
