package dev.jev.npc.behavior;

import dev.jev.npc.navigation.NavOutcome;
import net.minecraft.core.BlockPos;
import java.util.Optional;

/** Failures the task layer fixes by itself before anyone is asked. Everything else is escalated. */
public final class Recovery {
    public static final int MAX_ATTEMPTS = 2;
    private static final int SPARE_BLOCKS = 2;

    public static Optional<ActionPlan> plan(NavOutcome failure, BlockPos around, boolean mayDig) {
        if (failure instanceof NavOutcome.NeedBlocks need && mayDig) {
            int count = Math.min(16, need.needed() - need.carried() + SPARE_BLOCKS);
            return Optional.of(new ActionPlan("recover_blocks", Skill.MINE, around, null, "blocks", count,
                "Dig nearby natural dirt or stone to use as building blocks"));
        }
        return Optional.empty();
    }

    private Recovery() {}
}
