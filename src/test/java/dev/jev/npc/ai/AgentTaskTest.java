package dev.jev.npc.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentTaskTest {
    private static GoalPlan originalPlan() {
        return GoalPlan.parse(com.google.gson.JsonParser.parseString(DeepSeekClientTest.planJson()).getAsJsonObject());
    }
    private static GoalPlan revision(int count) {
        GoalPlan original = originalPlan();
        GoalIntent mine = new GoalIntent("mine", "stone", "owner", count, true);
        return new GoalPlan("交付修改后的总数再回家", original.constraints(), "交付并回家", java.util.List.of(
            new GoalPlan.Stage("交付圆石", mine, 0), new GoalPlan.Stage("回家", original.stages().get(1).method(), 1)));
    }

    @Test void amendmentCountsUnfinishedMiningOnceAndRetainsIdentityProvenanceAndBudgets() {
        AgentTask task = new AgentTask("十二块交给我再回家");
        task.setPlan(originalPlan());
        String id = task.id;
        task.collected.put("minecraft:cobblestone", 7);
        task.rounds = 5; task.elapsedTicks = 100;
        task.grants.add("break_built");
        task.amend("总共八块就够", revision(8), 7);
        assertEquals(id, task.id);
        assertEquals(7, task.gathered);
        assertEquals(8, task.intent.amount());
        assertEquals(7, task.collected.get("minecraft:cobblestone"));
        assertEquals(5, task.rounds);
        assertEquals(100, task.elapsedTicks);
        assertTrue(task.grants.contains("break_built"));
        assertFalse(task.stageComplete());
        assertNull(task.plan.stages().getFirst().fromStage(), "proposal references must be consumed");
        task.amend("改成九块", revision(9), 0);
        assertEquals(7, task.gathered, "repeated amendment must not count the old step twice");
        assertEquals(task.state(), AgentTask.load(task.save()).state());
    }

    @Test void amendmentCanReorderStagesWithoutLosingCompletedOrDeliveredEvidence() {
        AgentTask task = new AgentTask("十二块再回家");
        task.setPlan(originalPlan());
        task.gathered = 12; task.delivered = true;
        task.selectStage(1); task.actionSucceeded = true;
        GoalPlan revised = revision(13);
        task.amend("总数十三块", new GoalPlan(revised.objective(), revised.constraints(), revised.completion(),
            java.util.List.of(revised.stages().get(1), revised.stages().getFirst())), 0);
        assertEquals(0, task.stageIndex);
        assertTrue(task.stageComplete(0));
        assertFalse(task.canComplete());
        task.selectStage(1);
        assertEquals(12, task.gathered);
        assertFalse(task.delivered, "an increased cumulative quota is not yet delivered");
        assertTrue(task.collected.isEmpty(), "already delivered inventory cannot be recreated");
        task.gathered++; task.collected.put("minecraft:cobblestone", 1); task.delivered = false;
        assertFalse(task.canComplete());
        task.collected.clear(); task.deliveredCount++; task.delivered = true;
        assertTrue(task.canComplete());
    }

    @Test void revisedDeliveryQuotaIncludesPreviousTransfersAndRetainsExcessForLaterAmendment() {
        AgentTask task = new AgentTask("十二块再回家");
        task.setPlan(originalPlan());
        task.gathered = 9;
        task.deliveredCount = 3;
        task.collected.put("minecraft:cobblestone", 6);
        task.amend("总共八块", revision(8), 0);
        assertEquals(5, task.deliverableItems().get("minecraft:cobblestone"));
        task.deliveredCount += 5; task.collected.put("minecraft:cobblestone", 1); task.delivered = true;
        task = AgentTask.load(task.save());
        assertTrue(task.deliverableItems().isEmpty());
        task.amend("改为总共九块", revision(9), 0);
        assertFalse(task.stageComplete());
        assertEquals(1, task.deliverableItems().get("minecraft:cobblestone"), "re-use retained excess instead of mining again");
        task.amend("总共两块就够", revision(2), 0);
        assertTrue(task.stageComplete());
        assertEquals(8, task.deliveredAmount(), "already transferred items cannot be undone by changing the target");
        assertTrue(task.deliverableItems().isEmpty());
    }

    @Test void invalidAmendmentIsAtomicAndCannotCopyEvidenceToDifferentMethods() {
        AgentTask task = new AgentTask("十二块再回家");
        task.setPlan(originalPlan());
        task.collected.put("minecraft:cobblestone", 7);
        String before = task.save();
        var mine = revision(8).stages().getFirst();
        for (var stages : java.util.List.of(originalPlan().stages(), java.util.List.of(mine, mine),
            java.util.List.of(new GoalPlan.Stage("未知阶段", mine.method(), 7)),
            java.util.List.of(new GoalPlan.Stage("木头", new GoalIntent("harvest", "log", "owner", 8, true), 0)))) {
            GoalPlan invalid = new GoalPlan("修改", java.util.List.of(), "完成", stages);
            assertThrows(IllegalArgumentException.class, () -> task.amend("总共八块", invalid, 7));
            assertEquals(before, task.save(), "rejected amendment must leave all state intact");
        }
    }

    @Test void freshGoalCannotClaimPreviousStageEvidence() {
        AgentTask task = new AgentTask("新任务");
        assertThrows(IllegalArgumentException.class, () -> task.setPlan(revision(8)));
        assertNull(task.plan);
    }

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
    @Test void stagesPreserveProgressAndRequireActualDeliveryBeforeWholeGoalCompletion() {
        AgentTask task = new AgentTask("拿十二块圆石再回家");
        task.setPlan(GoalPlan.parse(com.google.gson.JsonParser.parseString(DeepSeekClientTest.planJson()).getAsJsonObject()));
        task.gathered = 7;
        task.collected.put("minecraft:cobblestone", 7);
        task.selectStage(1);
        task.actionSucceeded = true;
        assertFalse(task.canComplete());
        task = AgentTask.load(task.save());
        assertTrue(task.stageComplete(1));
        task.selectStage(0);
        assertEquals(7, task.gathered);
        assertEquals(7, task.collected.get("minecraft:cobblestone"));
        assertFalse(task.actionSucceeded);
        task.gathered = 12;
        assertFalse(task.canComplete(), "twelve mined is not twelve delivered");
        task.collected.clear();
        task.delivered = true;
        assertTrue(task.canComplete());
        assertEquals(task.state(), AgentTask.load(task.save()).state());
    }

    @Test void persistentActionCannotHideUnfinishedLaterWork() {
        String invalid = DeepSeekClientTest.planJson().replace("\"mine\"", "\"follow\"");
        assertThrows(IllegalArgumentException.class, () -> GoalPlan.parse(com.google.gson.JsonParser.parseString(invalid).getAsJsonObject()));
    }
}
