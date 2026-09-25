package dev.jev.npc.config;

/** Server-only configuration. Never include this object in logs or model state. */
public final class NpcConfig {
    public boolean enabled = true;
    public boolean traceEnabled = true;
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
    public int questionTimeoutTicks = 1200;
    public boolean autonomyEnabled = true;
    public int autonomyIdleTicks = 200;
    public boolean autonomyMayModifyWorld = true;
    public int autonomyHomeRadius = 24;
    /** Loaded from jev-npc.secret.json ({@code deepseekApiKey}); transient like {@link #apiKey}. */
    public transient String llmApiKey = "";
    public boolean llmEnabled = true;
    public String llmModel = "deepseek-flash";
    public int llmTimeoutMs = 20000;
    public int llmMaxRequestsPerMinute = 20;

    public void validate() {
        if (llmApiKey == null) llmApiKey = "";
        if (llmModel == null || llmModel.isBlank()) llmModel = "deepseek-flash";
        llmTimeoutMs = Math.clamp(llmTimeoutMs, 2000, 60000);
        llmMaxRequestsPerMinute = Math.clamp(llmMaxRequestsPerMinute, 1, 120);
        autonomyIdleTicks = Math.clamp(autonomyIdleTicks, 40, 12000);
        autonomyHomeRadius = Math.clamp(autonomyHomeRadius, 4, 128);
        questionTimeoutTicks = Math.clamp(questionTimeoutTicks, 200, 12000);
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

    public String effectiveLlmKey() {
        String environment = System.getenv("DEEPSEEK_API_KEY");
        return environment != null && !environment.isBlank() ? environment.trim() : llmApiKey.trim();
    }

    /** Whether Jev can offer the DeepSeek dialogue tool. */
    public boolean llmReady() { return llmEnabled && !effectiveLlmKey().isBlank(); }
}
