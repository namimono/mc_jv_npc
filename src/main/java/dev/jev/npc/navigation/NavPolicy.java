package dev.jev.npc.navigation;

/**
 * What the NPC may do on its own while travelling. {@code allowRisk} covers building over lava or jumping gaps above it;
 * {@code mayBreakBuilt} covers blocks that may belong to a player's build.
 */
public record NavPolicy(boolean mayBreak, boolean mayPlace, int maxFall, boolean allowRisk, boolean mayBreakBuilt) {
    public NavPolicy { maxFall = Math.clamp(maxFall, 1, 8); }

    public static NavPolicy walking(int maxFall) { return new NavPolicy(false, false, maxFall, false, false); }
    public NavPolicy withRisk() { return new NavPolicy(mayBreak, mayPlace, maxFall, true, mayBreakBuilt); }
    public NavPolicy withBuilt() { return new NavPolicy(mayBreak, mayPlace, maxFall, allowRisk, true); }
}
