package dev.jev.npc.ai;

public record Decision(String candidateId, double confidence, String model, int inputTokens, long elapsedMs) {}
