package dev.jev.npc.navigation;

import net.minecraft.core.BlockPos;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/** Where the NPC's feet should end up, in block coordinates. */
public sealed interface NavGoal {
    boolean reached(int x, int y, int z);
    double distance(int x, int y, int z);
    /** Whether the current path can be kept when a caller re-issues a slightly different goal. */
    boolean sameAs(NavGoal other);
    /** Exact goals ask the NPC to settle in the middle of the reached block. */
    boolean precise();

    default boolean reached(BlockPos pos) { return reached(pos.getX(), pos.getY(), pos.getZ()); }

    static NavGoal near(BlockPos pos, double radius) { return new Near(pos.immutable(), radius); }
    static NavGoal exact(BlockPos pos) { return new Near(pos.immutable(), 0); }
    static NavGoal anyOf(Collection<BlockPos> spots) {
        return new AnyOf(spots.stream().map(BlockPos::immutable).collect(Collectors.toUnmodifiableSet()));
    }

    record Near(BlockPos pos, double radius) implements NavGoal {
        public boolean reached(int x, int y, int z) { return distance(x, y, z) <= radius + 1e-6; }
        public double distance(int x, int y, int z) {
            double dx = x - pos.getX(), dy = y - pos.getY(), dz = z - pos.getZ();
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        public boolean sameAs(NavGoal other) {
            return other instanceof Near near && near.radius == radius && near.pos.distManhattan(pos) <= 2;
        }
        public boolean precise() { return radius == 0; }
    }

    record AnyOf(Set<BlockPos> spots) implements NavGoal {
        public boolean reached(int x, int y, int z) { return spots.contains(new BlockPos(x, y, z)); }
        public double distance(int x, int y, int z) {
            double best = Double.MAX_VALUE;
            for (BlockPos spot : spots) {
                double dx = x - spot.getX(), dy = y - spot.getY(), dz = z - spot.getZ();
                best = Math.min(best, dx * dx + dy * dy + dz * dz);
            }
            return Math.sqrt(best);
        }
        public boolean sameAs(NavGoal other) { return equals(other); }
        public boolean precise() { return true; }
    }
}
