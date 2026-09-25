package dev.jev.npc.config;

/** Server-only configuration. Never include this object in logs or model state. */
public final class NpcConfig {
    public boolean enabled = true;
    /** Loaded from jev-npc.secret.json. Transient so it is never written into jev-npc.json. */
    public transient String apiKey = "";
    public String model = "jev-1.13.0";
    public int requestTimeoutMs = 2500;
    public int decisionCooldownTicks = 40;
    public int eventDebounceTicks = 8;
    public int idleDecisionTicks = 600;
    public int maxResultAgeMs = 4000;
    public int maxRequestsPerMinute = 60;
    public double minimumConfidence = 0.35;
    public int maxGoalRounds = 16;
    public int maxGoalTicks = 3600;
    public boolean allowBlockChanges = true;
    public boolean debugToOwner = true;
    public boolean navAllowBreak = true;
    public boolean navAllowPlace = true;
    public int navMaxFall = 3;
    public int navMaxNodes = 6000;
    public int navNodesPerTick = 1500;

    public void validate() {
        navMaxFall = Math.clamp(navMaxFall, 1, 8);
        navMaxNodes = Math.clamp(navMaxNodes, 500, 50000);
        navNodesPerTick = Math.clamp(navNodesPerTick, 100, 10000);
        maxGoalRounds = Math.clamp(maxGoalRounds, 4, 64);
        maxGoalTicks = Math.clamp(maxGoalTicks, 200, 24000);
        if (apiKey == null) apiKey = "";
        if (model == null || model.isBlank()) model = "jev-1.13.0";
        requestTimeoutMs = Math.clamp(requestTimeoutMs, 250, 10000);
        decisionCooldownTicks = Math.clamp(decisionCooldownTicks, 20, 1200);
        eventDebounceTicks = Math.clamp(eventDebounceTicks, 1, 100);
        idleDecisionTicks = Math.clamp(idleDecisionTicks, 200, 12000);
        maxResultAgeMs = Math.clamp(maxResultAgeMs, 250, 15000);
        maxRequestsPerMinute = Math.clamp(maxRequestsPerMinute, 1, 600);
        if (!Double.isFinite(minimumConfidence)) minimumConfidence = 0.35;
        minimumConfidence = Math.clamp(minimumConfidence, 0, 1);
    }

    public String effectiveKey() {
        String environment = System.getenv("TYPESAFE_API_KEY");
        return environment != null && !environment.isBlank() ? environment.trim() : apiKey.trim();
    }
}
