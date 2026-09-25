package dev.jev.npc.navigation;

import dev.jev.npc.navigation.PathStep.Move;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PathPlannerTest {
    private static final NavPolicy FULL = new NavPolicy(true, true, 3, false, false);
    private static final NavPolicy WALK = NavPolicy.walking(3);

    /** Flat terrain: everything below y=0 is diggable dirt, everything above is open air. */
    private static final class Grid implements NavWorld {
        final Map<Long, Cell> cells = new HashMap<>();
        public Cell cell(int x, int y, int z) {
            return cells.getOrDefault(BlockPos.asLong(x, y, z), y < 0 ? Cell.solid(10, true) : Cell.open(true));
        }
        Grid set(int x, int y, int z, Cell cell) { cells.put(BlockPos.asLong(x, y, z), cell); return this; }
        Grid box(int x1, int y1, int z1, int x2, int y2, int z2, Cell cell) {
            for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) set(x, y, z, cell);
            return this;
        }
    }

    private static PathPlanner.Result plan(NavWorld world, BlockPos start, NavGoal goal, NavPolicy policy, int carried) {
        return plan(world, start, goal, policy, carried, 6000);
    }

    private static PathPlanner.Result plan(NavWorld world, BlockPos start, NavGoal goal, NavPolicy policy, int carried, int maxNodes) {
        PathPlanner planner = new PathPlanner(world, start, goal, policy, carried, maxNodes, 64);
        for (int slice = 0; slice < 1000; slice++) {
            var result = planner.step(500);
            if (result.isPresent()) return result.get();
        }
        throw new AssertionError("planner never finished");
    }

    private static List<PathStep> route(PathPlanner.Result result) {
        assertInstanceOf(PathPlanner.Route.class, result, () -> "expected a route but got " + result);
        return ((PathPlanner.Route) result).steps();
    }

    private static boolean uses(List<PathStep> steps, Move move) { return steps.stream().anyMatch(step -> step.move() == move); }

    @Test void walksAcrossFlatGround() {
        var steps = route(plan(new Grid(), BlockPos.ZERO, NavGoal.exact(new BlockPos(5, 0, 0)), WALK, 0));
        assertEquals(new BlockPos(5, 0, 0), steps.getLast().to());
        assertTrue(steps.stream().allMatch(step -> step.breaks().isEmpty() && step.place() == null));
    }

    @Test void detoursAroundLavaInsteadOfCrossingIt() {
        Grid grid = new Grid().box(2, -1, -2, 2, -1, 2, Cell.lava(true));
        var steps = route(plan(grid, BlockPos.ZERO, NavGoal.exact(new BlockPos(4, 0, 0)), FULL, 10));
        assertTrue(steps.stream().noneMatch(step -> step.to().getX() == 2 && Math.abs(step.to().getZ()) <= 2),
            "must never stand above the lava strip");
        assertTrue(steps.stream().allMatch(step -> step.place() == null), "must not bridge over lava without permission");
    }

    @Test void lavaRiverWithoutDetourNeedsPermissionForRisk() {
        Grid grid = new Grid().box(-64, -12, -64, 64, -2, 64, Cell.solid(-1, true)).box(2, -1, -64, 3, -1, 64, Cell.lava(true));
        var result = plan(grid, BlockPos.ZERO, NavGoal.exact(new BlockPos(5, 0, 0)), FULL, 10);
        assertEquals(new PathPlanner.Failure(new NavOutcome.NeedsPermission("risk", 0)), result);
        var granted = route(plan(grid, BlockPos.ZERO, NavGoal.exact(new BlockPos(5, 0, 0)), FULL.withRisk(), 10));
        assertTrue(uses(granted, Move.BRIDGE), "with permission the NPC bridges over the lava");
    }

    @Test void climbsOneBlockStep() {
        Grid grid = new Grid().box(2, 0, -64, 64, 0, 64, Cell.solid(10, true));
        var steps = route(plan(grid, BlockPos.ZERO, NavGoal.exact(new BlockPos(3, 1, 0)), WALK, 0));
        assertTrue(uses(steps, Move.ASCEND));
    }

    @Test void refusesFallsLongerThanPolicy() {
        Grid grid = new Grid().box(-64, -6, -64, 64, -1, 64, Cell.open(true)).box(-1, -1, -1, 1, -1, 1, Cell.solid(-1, true));
        var result = plan(grid, BlockPos.ZERO, NavGoal.exact(new BlockPos(3, -6, 0)), WALK, 0);
        assertInstanceOf(PathPlanner.Failure.class, result, "a six block drop exceeds maxFall=3");
    }

    @Test void bridgesGapWithCarriedBlocks() {
        Grid grid = gapIslands(3);
        var steps = route(plan(grid, BlockPos.ZERO, NavGoal.exact(new BlockPos(4, 0, 0)), FULL, 8));
        assertEquals(3, PathPlanner.placements(steps));
        assertTrue(uses(steps, Move.BRIDGE));
    }

    @Test void reportsHowManyBlocksAreMissing() {
        var result = plan(gapIslands(3), BlockPos.ZERO, NavGoal.exact(new BlockPos(4, 0, 0)), FULL, 1);
        assertEquals(new PathPlanner.Failure(new NavOutcome.NeedBlocks(3, 1)), result);
    }

    @Test void jumpsSingleGapWithoutBlocks() {
        var steps = route(plan(gapIslands(1), BlockPos.ZERO, NavGoal.exact(new BlockPos(2, 0, 0)), WALK, 0));
        assertTrue(uses(steps, Move.PARKOUR));
    }

    @Test void pillarsUpStackedBlocks() {
        Grid grid = new Grid().box(-64, -1, -64, 64, -1, 64, Cell.solid(-1, true)).box(1, 0, 0, 1, 2, 0, Cell.solid(-1, false));
        var steps = route(plan(grid, BlockPos.ZERO, NavGoal.exact(new BlockPos(1, 3, 0)), new NavPolicy(false, true, 3, false, false), 5));
        assertTrue(steps.stream().filter(step -> step.move() == Move.PILLAR).count() >= 2, "stacked pillars must see earlier placements");
    }

    @Test void digsThroughTerrainButAsksBeforeBreakingBuiltBlocks() {
        Grid terrain = enclosure(Cell.solid(10, true));
        var dug = route(plan(terrain, BlockPos.ZERO, NavGoal.exact(new BlockPos(4, 0, 0)), FULL, 0));
        assertTrue(dug.stream().anyMatch(step -> !step.breaks().isEmpty()));
        Grid built = enclosure(Cell.solid(10, false));
        var result = plan(built, BlockPos.ZERO, NavGoal.exact(new BlockPos(4, 0, 0)), FULL, 0);
        assertInstanceOf(PathPlanner.Failure.class, result);
        var outcome = ((PathPlanner.Failure) result).outcome();
        assertInstanceOf(NavOutcome.NeedsPermission.class, outcome);
        assertEquals("break_built", ((NavOutcome.NeedsPermission) outcome).kind());
        assertTrue(((NavOutcome.NeedsPermission) outcome).blocks() >= 2);
        assertEquals(new PathPlanner.Failure(new NavOutcome.Unreachable("no_path")),
            plan(built, BlockPos.ZERO, NavGoal.exact(new BlockPos(4, 0, 0)), WALK, 0));
    }

    @Test void budgetExhaustionFollowsPartialPathTowardsFarGoal() {
        var result = plan(new Grid(), BlockPos.ZERO, NavGoal.exact(new BlockPos(60, 0, 0)), WALK, 0, 60);
        assertInstanceOf(PathPlanner.Route.class, result);
        var route = (PathPlanner.Route) result;
        assertTrue(route.partial());
        assertTrue(route.steps().getLast().to().getX() > 3);
    }

    @Test void anyOfGoalStopsAtFirstReachableSpot() {
        Grid grid = new Grid().box(2, 0, -64, 2, 1, 64, Cell.solid(-1, false));
        var steps = route(plan(grid, BlockPos.ZERO, NavGoal.anyOf(List.of(new BlockPos(1, 0, 0), new BlockPos(3, 0, 0))), WALK, 0));
        assertEquals(new BlockPos(1, 0, 0), steps.getLast().to());
    }

    /** Two floating islands separated by {@code gap} open columns; nothing below to land on. */
    private static Grid gapIslands(int gap) {
        Grid grid = new Grid().box(-64, -12, -64, 64, -1, 64, Cell.open(true));
        grid.box(-1, -1, -1, 0, -1, 1, Cell.solid(-1, true));
        grid.box(gap + 1, -1, -1, gap + 2, -1, 1, Cell.solid(-1, true));
        return grid;
    }

    /** The NPC stands inside a sealed two-high room; the goal lies outside. */
    private static Grid enclosure(Cell wall) {
        Grid grid = new Grid().box(-64, -1, -64, 64, -1, 64, Cell.solid(-1, true));
        grid.box(-1, 0, -1, 1, 2, 1, wall).set(0, 0, 0, Cell.open(true)).set(0, 1, 0, Cell.open(true));
        return grid;
    }
}
