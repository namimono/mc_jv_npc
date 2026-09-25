package dev.jev.npc.navigation;

import net.minecraft.core.BlockPos;
import java.util.List;
import java.util.Optional;

/**
 * Plans a route in budgeted slices. When the real search proves there is no route, it re-plans under relaxed
 * assumptions to report what would have made one possible: more blocks, accepting risk, or breaking built blocks.
 */
public final class PathPlanner {
    public sealed interface Result permits Route, Failure {}
    public record Route(List<PathStep> steps, boolean partial) implements Result {}
    public record Failure(NavOutcome outcome) implements Result {}

    private enum Phase { REAL, BLOCKS, RISK, BUILT }
    private static final int UNLIMITED = Integer.MAX_VALUE / 2;
    private static final double PARTIAL_PROGRESS = 3;

    private final NavWorld world;
    private final BlockPos start;
    private final NavGoal goal;
    private final NavPolicy policy;
    private final int carried, maxNodes, range;
    private Phase phase = Phase.REAL;
    private PathSearch search;
    private int blockLimited, riskLimited, builtLimited, unloaded;
    private boolean budgetExhausted;

    public PathPlanner(NavWorld world, BlockPos start, NavGoal goal, NavPolicy policy, int carried, int maxNodes, int range) {
        this.world = world;
        this.start = start.immutable();
        this.goal = goal;
        this.policy = policy;
        this.carried = carried;
        this.maxNodes = maxNodes;
        this.range = range;
        this.search = new PathSearch(world, this.start, goal, policy, carried, maxNodes, range);
    }

    /** Empty while the search needs more budget. */
    public Optional<Result> step(int budget) {
        PathSearch.State state = search.step(budget);
        if (state == PathSearch.State.SEARCHING) return Optional.empty();
        boolean found = state == PathSearch.State.FOUND;
        return switch (phase) {
            case REAL -> {
                if (found) yield Optional.of(new Route(search.path(), false));
                if (state == PathSearch.State.BUDGET) {
                    budgetExhausted = true;
                    List<PathStep> partial = search.partialPath();
                    if (!partial.isEmpty() && search.bestDistance() <= search.startDistance() - PARTIAL_PROGRESS)
                        yield Optional.of(new Route(partial, true));
                }
                blockLimited = search.blockLimited;
                riskLimited = search.riskLimited;
                builtLimited = search.builtLimited;
                unloaded = search.unloaded;
                yield next();
            }
            case BLOCKS -> found ? Optional.of(new Failure(new NavOutcome.NeedBlocks(placements(search.path()), carried))) : next();
            case RISK -> found ? Optional.of(new Failure(new NavOutcome.NeedsPermission("risk", 0))) : next();
            case BUILT -> found ? Optional.of(new Failure(new NavOutcome.NeedsPermission("break_built", builtBreaks(search.path())))) : next();
        };
    }

    private Optional<Result> next() {
        if (phase.ordinal() < Phase.BLOCKS.ordinal() && blockLimited > 0 && policy.mayPlace()) return begin(Phase.BLOCKS, policy);
        if (phase.ordinal() < Phase.RISK.ordinal() && riskLimited > 0 && !policy.allowRisk()) return begin(Phase.RISK, policy.withRisk());
        if (phase.ordinal() < Phase.BUILT.ordinal() && builtLimited > 0 && !policy.mayBreakBuilt()) return begin(Phase.BUILT, policy.withBuilt());
        String reason = budgetExhausted ? "too_far" : unloaded > 0 ? "unloaded" : "no_path";
        return Optional.of(new Failure(new NavOutcome.Unreachable(reason)));
    }

    private Optional<Result> begin(Phase next, NavPolicy relaxed) {
        phase = next;
        search = new PathSearch(world, start, goal, relaxed, UNLIMITED, maxNodes, range);
        return Optional.empty();
    }

    static int placements(List<PathStep> steps) { return (int) steps.stream().filter(step -> step.place() != null).count(); }

    private int builtBreaks(List<PathStep> steps) {
        return (int) steps.stream().flatMap(step -> step.breaks().stream()).filter(pos -> !world.cell(pos).terrain()).count();
    }
}
