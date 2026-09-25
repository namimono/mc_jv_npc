package dev.jev.npc.navigation;

import net.minecraft.core.BlockPos;
import java.util.List;

/** One movement between standable positions; blocks in {@code breaks} are cleared first, then {@code place} is filled. */
public record PathStep(Move move, BlockPos from, BlockPos to, List<BlockPos> breaks, BlockPos place) {
    public enum Move { WALK, DIAGONAL, ASCEND, FALL, SWIM_UP, PARKOUR, BRIDGE, PILLAR, DIG_DOWN }
}
