package dev.jev.npc.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentTaskTest {
    @Test void collectingIsNotCompleteUntilDeliveryAndPartialWorkIsNotSuccess() {
        AgentTask task = new AgentTask("帮我搞点木头");
        task.intent = new GoalIntent("harvest", "log", "owner", 4, true);
        task.gathered = 1;
        task.feedback("harvest", false, 1, "remaining log blocked");
        assertFalse(task.canComplete());
        task.gathered = 4;
        assertFalse(task.canComplete());
        task.delivered = true;
        assertTrue(task.canComplete());
    }

    @Test void searchCannotCompleteAMovementGoal() {
        AgentTask task = new AgentTask("去水里");
        task.intent = new GoalIntent("go_to", "log", "water", 1, false);
        task.feedback("observe_nearby", true, 2, "two water targets; reachability unknown");
        assertFalse(task.canComplete());
        task.actionSucceeded = true;
        assertTrue(task.canComplete());
    }

    @Test void saveReloadRetainsGoalEvidenceProgressAndLimits() {
        AgentTask task = new AgentTask("帮我搞点木头");
        task.intent = new GoalIntent("harvest", "log", "owner", 4, true);
        task.gathered = 2;
        task.rounds = 7;
        task.elapsedTicks = 120;
        task.collected.put("minecraft:oak_log", 2);
        task.failedTargets.add("work_1_2_3");
        task.feedback("work_1_2_3", false, 2, "unreachable remaining blocks");
        AgentTask restored = AgentTask.load(task.save());
        assertEquals(task.state(), restored.state());
        assertEquals(task.id, restored.id);
        assertTrue(restored.exhausted(7, 3600));
        assertTrue(restored.exhausted(16, 120));
        assertFalse(restored.exhausted(16, 3600));
        for (int i = 0; i < 4; i++) restored.feedback("model", false, 0, "timeout");
        assertTrue(restored.exhausted(16, 3600));
    }

    @Test void evidenceHistoryIsBoundedButGoalRemains() {
        AgentTask task = new AgentTask("去水里");
        for (int i = 0; i < 30; i++) task.feedback("observe", true, 0, "empty");
        assertEquals(12, task.history.size());
        assertEquals("去水里", task.state().get("request").getAsString());
    }
}
