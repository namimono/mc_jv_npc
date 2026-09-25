package dev.jev.npc.navigation;

import dev.jev.npc.navigation.Cell.Kind;
import dev.jev.npc.navigation.PathStep.Move;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;

/** Incremental A* over standable positions. All world knowledge comes from the {@link NavWorld}. */
final class PathSearch {
    enum State { SEARCHING, FOUND, EXHAUSTED, BUDGET }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] CORNERS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    // Own edits are remembered for the last few steps so stacked pillars and tunnels see earlier changes.
    private static final int EDIT_LOOKBACK = 8;
    private static final double LAVA_NEARBY_COST = 4;
    // A jump can miss; with blocks at hand, bridging the same gap (3 + 1) is preferred.
    private static final double PARKOUR_COST = 5;

    private record Edit(long pos, boolean solid, Edit next) {}
    private record Entry(Node node, double f, double h) {}

    private static final class Node {
        final int x, y, z;
        double g = Double.MAX_VALUE, h;
        Node parent;
        PathStep step;
        int placed;
        Edit edits;
        boolean closed;
        Node(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    private final NavWorld world;
    private final NavGoal goal;
    private final NavPolicy policy;
    private final int carried, maxNodes, range;
    private final Node first;
    private final HashMap<Long, Node> nodes = new HashMap<>();
    private final PriorityQueue<Entry> open = new PriorityQueue<>(
        Comparator.comparingDouble(Entry::f).thenComparingDouble(Entry::h));
    private Node best, found;
    private State state = State.SEARCHING;
    int blockLimited, riskLimited, builtLimited, unloaded;

    PathSearch(NavWorld world, BlockPos start, NavGoal goal, NavPolicy policy, int carried, int maxNodes, int range) {
        this.world = world;
        this.goal = goal;
        this.policy = policy;
        this.carried = carried;
        this.maxNodes = maxNodes;
        this.range = range;
        first = node(start.getX(), start.getY(), start.getZ());
        first.g = 0;
        first.h = goal.distance(first.x, first.y, first.z);
        best = first;
        open.add(new Entry(first, first.h, first.h));
    }

    State step(int budget) {
        while (state == State.SEARCHING && budget-- > 0) {
            Entry entry = open.poll();
            if (entry == null) { state = State.EXHAUSTED; break; }
            Node node = entry.node();
            if (node.closed || entry.f() > node.g + node.h + 1e-9) continue;
            node.closed = true;
            if (goal.reached(node.x, node.y, node.z)) { found = node; state = State.FOUND; break; }
            if (node.h < best.h) best = node;
            expand(node);
            if (nodes.size() >= maxNodes) state = State.BUDGET;
        }
        return state;
    }

    List<PathStep> path() { return trace(found); }
    List<PathStep> partialPath() { return trace(best); }
    double startDistance() { return first.h; }
    double bestDistance() { return best.h; }

    private static List<PathStep> trace(Node end) {
        LinkedList<PathStep> steps = new LinkedList<>();
        for (Node node = end; node != null && node.parent != null; node = node.parent) steps.addFirst(node.step);
        return List.copyOf(steps);
    }

    private void expand(Node p) {
        boolean inWater = cell(p, p.x, p.y, p.z).kind() == Kind.WATER;
        for (int[] side : SIDES) {
            horizontal(p, side);
            ascend(p, p.x + side[0], p.z + side[1]);
        }
        for (int[] corner : CORNERS) diagonal(p, corner);
        pillar(p, inWater);
        digDown(p);
        if (inWater) swimUp(p);
    }

    private void horizontal(Node p, int[] side) {
        int qx = p.x + side[0], qz = p.z + side[1], y = p.y;
        List<BlockPos> breaks = new ArrayList<>(2);
        double head = clear(p, qx, y + 1, qz, breaks);
        if (head < 0) return;
        double feet = clear(p, qx, y, qz, breaks);
        if (feet < 0) return;
        boolean water = cell(p, qx, y, qz).kind() == Kind.WATER;
        Cell floor = cell(p, qx, y - 1, qz);
        if (floor.supports() || water) {
            add(p, qx, y, qz, Move.WALK, 1 + head + feet + (water ? 1.5 : 0), breaks, null, false);
            return;
        }
        if (!breaks.isEmpty()) return;
        if (floor.kind() == Kind.UNLOADED) { unloaded++; return; }
        if (policy.mayPlace() && floor.placeable() && (floor.passable() || floor.kind() == Kind.LAVA))
            add(p, qx, y, qz, Move.BRIDGE, 3, List.of(), new BlockPos(qx, y - 1, qz), lavaColumn(p, qx, y - 1, qz));
        fall(p, qx, qz);
        parkour(p, qx, qz, side);
    }

    /** Lava in this cell or up to four blocks below it: standing on a block built here means hovering over lava. */
    private boolean lavaColumn(Node p, int x, int y, int z) {
        for (int depth = 0; depth <= 4; depth++) if (cell(p, x, y - depth, z).kind() == Kind.LAVA) return true;
        return false;
    }

    private void fall(Node p, int qx, int qz) {
        for (int drop = 1; drop <= 12; drop++) {
            int fy = p.y - drop;
            Cell c = cell(p, qx, fy, qz);
            if (c.kind() == Kind.UNLOADED) { unloaded++; return; }
            if (c.kind() == Kind.WATER) { add(p, qx, fy, qz, Move.FALL, 1 + drop * 0.5, List.of(), null, false); return; }
            if (!c.passable()) return;
            Cell below = cell(p, qx, fy - 1, qz);
            if (below.supports()) {
                if (drop <= policy.maxFall()) add(p, qx, fy, qz, Move.FALL, 1 + drop * 0.5, List.of(), null, false);
                return;
            }
            if (!below.passable()) return;
        }
    }

    private void parkour(Node p, int qx, int qz, int[] side) {
        int y = p.y, tx = qx + side[0], tz = qz + side[1];
        if (cell(p, qx, y - 1, qz).kind() == Kind.WATER || !cell(p, p.x, y - 1, p.z).supports()) return;
        if (!cell(p, p.x, y + 2, p.z).passable() || !cell(p, qx, y + 2, qz).passable()) return;
        if (!cell(p, tx, y, tz).passable() || !cell(p, tx, y + 1, tz).passable() || !cell(p, tx, y - 1, tz).supports()) return;
        add(p, tx, y, tz, Move.PARKOUR, PARKOUR_COST, List.of(), null, lavaColumn(p, qx, y - 1, qz));
    }

    private void ascend(Node p, int qx, int qz) {
        int y = p.y;
        if (!cell(p, qx, y, qz).supports()) return;
        List<BlockPos> breaks = new ArrayList<>(3);
        double above = clear(p, p.x, y + 2, p.z, breaks);
        if (above < 0) return;
        double head = clear(p, qx, y + 2, qz, breaks);
        if (head < 0) return;
        double feet = clear(p, qx, y + 1, qz, breaks);
        if (feet < 0) return;
        add(p, qx, y + 1, qz, Move.ASCEND, 2 + above + head + feet, breaks, null, false);
    }

    private void diagonal(Node p, int[] corner) {
        int qx = p.x + corner[0], qz = p.z + corner[1], y = p.y;
        if (!walkable(p, p.x + corner[0], y, p.z) || !walkable(p, p.x, y, p.z + corner[1]) || !walkable(p, qx, y, qz)) return;
        add(p, qx, y, qz, Move.DIAGONAL, 1.414, List.of(), null, false);
    }

    private boolean walkable(Node p, int x, int y, int z) {
        return cell(p, x, y, z).kind() == Kind.OPEN && cell(p, x, y + 1, z).passable() && cell(p, x, y - 1, z).supports();
    }

    private void pillar(Node p, boolean inWater) {
        if (!policy.mayPlace() || inWater || !cell(p, p.x, p.y - 1, p.z).supports()) return;
        if (!cell(p, p.x, p.y, p.z).placeable()) return;
        List<BlockPos> breaks = new ArrayList<>(1);
        double head = clear(p, p.x, p.y + 2, p.z, breaks);
        if (head < 0) return;
        add(p, p.x, p.y + 1, p.z, Move.PILLAR, 4 + head, breaks, new BlockPos(p.x, p.y, p.z), false);
    }

    private void digDown(Node p) {
        if (!policy.mayBreak() || !cell(p, p.x, p.y - 1, p.z).supports() || !cell(p, p.x, p.y - 2, p.z).supports()) return;
        List<BlockPos> breaks = new ArrayList<>(1);
        double cost = clear(p, p.x, p.y - 1, p.z, breaks);
        if (cost < 0) return;
        add(p, p.x, p.y - 1, p.z, Move.DIG_DOWN, 1 + cost, breaks, null, false);
    }

    private void swimUp(Node p) {
        if (cell(p, p.x, p.y + 1, p.z).kind() != Kind.WATER || !cell(p, p.x, p.y + 2, p.z).passable()) return;
        add(p, p.x, p.y + 1, p.z, Move.SWIM_UP, 2, List.of(), null, false);
    }

    /** Extra cost for the body to occupy this cell, or -1 when it cannot be cleared. */
    private double clear(Node p, int x, int y, int z, List<BlockPos> breaks) {
        Cell c = cell(p, x, y, z);
        if (c.passable()) return 0;
        if (c.kind() == Kind.UNLOADED) { unloaded++; return -1; }
        if (!policy.mayBreak() || c.breakTicks() < 0 || (c.kind() != Kind.SOLID && c.kind() != Kind.OBSTACLE)) return -1;
        if (!c.terrain() && !policy.mayBreakBuilt()) { builtLimited++; return -1; }
        breaks.add(new BlockPos(x, y, z));
        return 1 + c.breakTicks() / 4.0;
    }

    private void add(Node p, int x, int y, int z, Move move, double cost, List<BlockPos> breaks, BlockPos place, boolean risky) {
        if (Math.abs(x - first.x) > range || Math.abs(y - first.y) > range || Math.abs(z - first.z) > range) return;
        if (risky && !policy.allowRisk()) { riskLimited++; return; }
        int placed = p.placed + (place == null ? 0 : 1);
        if (placed > carried) { blockLimited++; return; }
        double g = p.g + cost + (lavaNearby(p, x, y, z) ? LAVA_NEARBY_COST : 0);
        Node n = node(x, y, z);
        if (n.closed || g >= n.g) return;
        n.g = g;
        n.parent = p;
        n.placed = placed;
        n.step = new PathStep(move, new BlockPos(p.x, p.y, p.z), new BlockPos(x, y, z), List.copyOf(breaks), place);
        Edit edits = p.edits;
        for (BlockPos broken : breaks) edits = new Edit(broken.asLong(), false, edits);
        if (place != null) edits = new Edit(place.asLong(), true, edits);
        n.edits = edits;
        n.h = goal.distance(x, y, z);
        open.add(new Entry(n, g + n.h, n.h));
    }

    private boolean lavaNearby(Node p, int x, int y, int z) {
        for (int[] side : SIDES) {
            if (cell(p, x + side[0], y, z + side[1]).kind() == Kind.LAVA || cell(p, x + side[0], y - 1, z + side[1]).kind() == Kind.LAVA)
                return true;
        }
        return false;
    }

    private Cell cell(Node p, int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        int depth = 0;
        for (Edit edit = p.edits; edit != null && depth < EDIT_LOOKBACK; edit = edit.next(), depth++)
            if (edit.pos() == key) return edit.solid() ? Cell.PLACED : Cell.CLEARED;
        return world.cell(x, y, z);
    }

    private Node node(int x, int y, int z) {
        return nodes.computeIfAbsent(BlockPos.asLong(x, y, z), key -> new Node(x, y, z));
    }
}
