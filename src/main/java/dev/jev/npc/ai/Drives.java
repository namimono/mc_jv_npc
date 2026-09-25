package dev.jev.npc.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The NPC's own needs when nobody is giving orders. Code measures them from observed state; Jev (or, without a key,
 * {@link #choose}) picks what to do about them. Levels are 0..1 before personality weighting.
 */
public final class Drives {
    public record State(float health, float maxHealth, int bread, int buildingBlocks, int logs, boolean night,
                        double homeDistance, double ownerDistance, boolean enemyNear, long idleTicks, String personality,
                        boolean mayGather, boolean diggableNearby, boolean treesNearby) {}

    /** {@code action} is the autonomous candidate id that serves this need. */
    public record Need(String id, String action, double level, String reason) {}

    public static final double THRESHOLD = 0.25;
    static final int BLOCK_STOCK = 16;
    static final int LOG_STOCK = 8;
    private static final Map<String, Map<String, Double>> WEIGHTS = Map.of(
        "cautious", Map.of("safety", 1.5, "heal", 1.3, "company", 1.2, "defend", 0.8, "wander", 0.6),
        "brave", Map.of("defend", 1.5, "safety", 0.8, "wander", 1.2),
        "diligent", Map.of("stock_blocks", 1.5, "stock_wood", 1.4, "company", 0.9, "wander", 0.5));

    public static List<Need> needs(State s) {
        List<Need> needs = new ArrayList<>();
        if (s.enemyNear())
            needs.add(s.health() > 12 ? new Need("defend", "attack_enemy", 0.7, "a hostile mob is close")
                : new Need("defend", "flee_enemy", 0.9, "a hostile mob is close and health is low"));
        if (s.health() < s.maxHealth() && s.bread() > 0)
            needs.add(new Need("heal", "eat_bread", 1 - s.health() / s.maxHealth(), "health " + Math.round(s.health()) + "/" + Math.round(s.maxHealth())));
        if (s.night() && s.homeDistance() > 16)
            needs.add(new Need("safety", "return_home", 0.8, "night has fallen " + Math.round(s.homeDistance()) + " blocks from home"));
        if (s.ownerDistance() > 16)
            needs.add(new Need("company", "rejoin_owner", Math.min(1, 0.5 + (s.ownerDistance() - 16) / 32), "owner is " + Math.round(s.ownerDistance()) + " blocks away"));
        if (s.mayGather() && s.diggableNearby() && s.buildingBlocks() < BLOCK_STOCK)
            needs.add(new Need("stock_blocks", "stock_blocks", 0.3 + 0.4 * (1 - s.buildingBlocks() / (double) BLOCK_STOCK),
                "only " + s.buildingBlocks() + " building blocks for bridging or pillaring"));
        if (s.mayGather() && s.treesNearby() && s.logs() < LOG_STOCK)
            needs.add(new Need("stock_wood", "stock_wood", 0.25 + 0.35 * (1 - s.logs() / (double) LOG_STOCK), "only " + s.logs() + " logs"));
        if (s.idleTicks() > 1200) needs.add(new Need("wander", "wander", 0.2, "idle for " + s.idleTicks() / 20 + " seconds"));
        needs.sort(Comparator.comparingDouble((Need need) -> weighted(need, s.personality())).reversed());
        return needs;
    }

    public static double weighted(Need need, String personality) {
        return need.level() * WEIGHTS.getOrDefault(personality, Map.of()).getOrDefault(need.id(), 1.0);
    }

    /** Local choice without a model: the most pressing weighted need, if any is pressing enough. */
    public static Optional<Need> choose(List<Need> needs, String personality) {
        return needs.stream().filter(need -> weighted(need, personality) >= THRESHOLD)
            .max(Comparator.comparingDouble(need -> weighted(need, personality)));
    }

    public static JsonArray json(List<Need> needs, String personality) {
        JsonArray array = new JsonArray();
        for (Need need : needs) {
            JsonObject item = new JsonObject();
            item.addProperty("need", need.id());
            item.addProperty("urgency", Math.round(weighted(need, personality) * 100) / 100.0);
            item.addProperty("reason", need.reason());
            item.addProperty("served_by", need.action());
            array.add(item);
        }
        return array;
    }

    private Drives() {}
}
