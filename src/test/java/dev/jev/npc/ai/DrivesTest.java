package dev.jev.npc.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DrivesTest {
    private static Drives.State calm(String personality) {
        return new Drives.State(30, 30, 8, 32, 16, false, 2, 3, false, 0, personality, true, true, true);
    }

    @Test void contentNpcHasNothingPressing() {
        assertTrue(Drives.choose(Drives.needs(calm("cautious")), "cautious").isEmpty());
    }

    @Test void nightFarFromHomeSendsCautiousNpcHome() {
        var state = new Drives.State(30, 30, 8, 0, 0, true, 60, 3, false, 0, "cautious", true, true, true);
        assertEquals("return_home", Drives.choose(Drives.needs(state), "cautious").orElseThrow().action());
    }

    @Test void diligentNpcStocksBuildingBlocksWhenIdle() {
        var state = new Drives.State(30, 30, 8, 2, 16, false, 2, 3, false, 0, "diligent", true, true, false);
        assertEquals("stock_blocks", Drives.choose(Drives.needs(state), "diligent").orElseThrow().action());
    }

    @Test void gatheringIsNeverChosenWhenWorldChangesAreNotAllowed() {
        var state = new Drives.State(30, 30, 8, 0, 0, false, 2, 3, false, 0, "diligent", false, true, true);
        assertTrue(Drives.needs(state).stream().noneMatch(need -> need.id().startsWith("stock")));
    }

    @Test void lowHealthTurnsDefenceIntoRetreat() {
        var state = new Drives.State(8, 30, 0, 32, 16, false, 2, 3, true, 0, "brave", true, true, true);
        assertEquals("flee_enemy", Drives.choose(Drives.needs(state), "brave").orElseThrow().action());
    }

    @Test void personalityChangesTheRanking() {
        var state = new Drives.State(20, 30, 8, 8, 16, false, 2, 20, false, 0, "cautious", true, true, false);
        assertEquals("rejoin_owner", Drives.choose(Drives.needs(state), "cautious").orElseThrow().action());
        var diligent = new Drives.State(20, 30, 8, 8, 16, false, 2, 20, false, 0, "diligent", true, true, false);
        assertEquals("stock_blocks", Drives.choose(Drives.needs(diligent), "diligent").orElseThrow().action());
    }
}
