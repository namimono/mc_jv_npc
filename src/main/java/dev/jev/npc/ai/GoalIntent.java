package dev.jev.npc.ai;

/** Typed semantic interpretation; coordinates and entity identities remain code-owned. */
public record GoalIntent(String verb, String material, String place, int amount, boolean deliverToOwner) {
    public boolean gathering() { return verb.equals("harvest") || verb.equals("mine"); }
}
