package dev.jev.npc.ai;

public record Decision(String candidateId, double confidence, String model, int inputTokens, long elapsedMs, GoalIntent intent, String diagnostics) {
    public Decision(String candidateId, double confidence, String model, int inputTokens, long elapsedMs) {
        this(candidateId, confidence, model, inputTokens, elapsedMs, null, "");
    }
}
