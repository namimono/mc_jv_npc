package dev.jev.npc.behavior;

import dev.jev.npc.navigation.NavOutcome;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryTest {
    @Test void missingBlocksAreDugWithSpares() {
        var plan = Recovery.plan(new NavOutcome.NeedBlocks(3, 1), BlockPos.ZERO, true).orElseThrow();
        assertEquals(Skill.MINE, plan.skill());
        assertEquals("blocks", plan.argument());
        assertEquals(4, plan.count());
        assertEquals(16, Recovery.plan(new NavOutcome.NeedBlocks(40, 0), BlockPos.ZERO, true).orElseThrow().count());
    }

    @Test void judgmentCallsAreNeverAutoRecovered() {
        assertTrue(Recovery.plan(new NavOutcome.NeedsPermission("risk", 0), BlockPos.ZERO, true).isEmpty());
        assertTrue(Recovery.plan(new NavOutcome.NeedsPermission("break_built", 3), BlockPos.ZERO, true).isEmpty());
        assertTrue(Recovery.plan(new NavOutcome.Unreachable("no_path"), BlockPos.ZERO, true).isEmpty());
        assertTrue(Recovery.plan(new NavOutcome.NeedBlocks(3, 0), BlockPos.ZERO, false).isEmpty(), "digging disabled");
    }
}
