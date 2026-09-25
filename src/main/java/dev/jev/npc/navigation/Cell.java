package dev.jev.npc.navigation;

/** What the planner knows about one block position. {@code breakTicks < 0} means it must not be broken. */
public record Cell(Kind kind, int breakTicks, boolean terrain, boolean placeable) {
    public enum Kind { OPEN, WATER, SOLID, OBSTACLE, LAVA, HAZARD, UNLOADED }

    public static final Cell UNLOADED = new Cell(Kind.UNLOADED, -1, false, false);
    public static final Cell HAZARD = new Cell(Kind.HAZARD, -1, false, false);
    static final Cell PLACED = new Cell(Kind.SOLID, -1, false, false);
    static final Cell CLEARED = new Cell(Kind.OPEN, -1, false, true);

    public static Cell open(boolean placeable) { return new Cell(Kind.OPEN, -1, false, placeable); }
    public static Cell water(boolean placeable) { return new Cell(Kind.WATER, -1, false, placeable); }
    public static Cell lava(boolean placeable) { return new Cell(Kind.LAVA, -1, false, placeable); }
    public static Cell solid(int breakTicks, boolean terrain) { return new Cell(Kind.SOLID, breakTicks, terrain, false); }
    public static Cell obstacle(int breakTicks, boolean terrain) { return new Cell(Kind.OBSTACLE, breakTicks, terrain, false); }

    public boolean passable() { return kind == Kind.OPEN || kind == Kind.WATER; }
    public boolean supports() { return kind == Kind.SOLID; }
}
