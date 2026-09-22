package dev.jev.npc.config;

/** Server-only configuration. Never include this object in logs or model state. */
public final class NpcConfig {
    public boolean enabled = true;
    public String apiKey = "";
    public String model = "jev-1.13.0";
    public int requestTimeoutMs = 2500;
    public int decisionCooldownTicks = 40;
    public int eventDebounceTicks = 8;
    public int idleDecisionTicks = 600;
    public int maxResultAgeMs = 4000;
    public int maxRequestsPerMinute = 60;
    public double minimumConfidence = 0.35;
    public boolean allowBlockChanges = true;
    public boolean debugToOwner = true;

    public void validate() {
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
