package dev.jev.npc.ai;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DialogueSessionTest {
    private final DialogueSession session = new DialogueSession();
    private DeepSeekClient.Reply proposal() {
        return new DeepSeekClient.Reply("先采集再回家", GoalPlan.parse(JsonParser.parseString(DeepSeekClientTest.planJson()).getAsJsonObject()), "replace", "", 0, 0);
    }
    private void begin() { session.begin(DialogueSession.Mode.UNDERSTAND_PLAYER, "拿十二块圆石再回家", "player_message", "goal:1"); }

    @Test void generationRequiresJevAndItsResultRequiresASecondJevChoice() {
        begin();
        long id = session.request().id();
        assertFalse(session.generated(id, proposal()));
        assertThrows(IllegalArgumentException.class, () -> session.select("adopt_plan", true, false));
        assertEquals(DialogueSession.Effect.GENERATE, session.select("consult_dialogue", true, false));
        assertTrue(session.options(true, false).isEmpty());
        assertTrue(session.generated(id, proposal()));
        assertEquals(DialogueSession.Phase.REVIEW, session.phase());
        assertFalse(session.options(true, false).stream().anyMatch(c -> c.id().equals("consult_dialogue")), "a proposal cannot delegate itself again");
        assertEquals(DialogueSession.Effect.ADOPT, session.select("adopt_plan", true, false));
        session.clear();
        assertFalse(session.generated(id, proposal()));
        assertThrows(IllegalArgumentException.class, () -> session.select("adopt_plan", true, false));
    }
    @Test void newerMessageAndCancellationInvalidateInFlightLanguageResult() {
        begin();
        session.select("consult_dialogue", true, false);
        long old = session.request().id();
        begin();
        assertFalse(session.generated(old, proposal()));
        session.select("consult_dialogue", true, false);
        long cancelled = session.request().id();
        session.clear();
        assertFalse(session.generated(cancelled, proposal()));
    }
    @Test void compositionCannotAdoptCancelOrGrantEvenIfProviderReturnsAPlan() {
        session.begin(DialogueSession.Mode.COMPOSE_SPEECH, "天黑了", "nightfall", "goal:1");
        session.select("consult_dialogue", true, true);
        assertTrue(session.generated(session.request().id(), new DeepSeekClient.Reply("天黑了", proposal().plan(), "cancel", "yes", 0, 0)));
        assertFalse(session.reply().hasTask());
        for (String forbidden : new String[]{"adopt_plan", "stop_requested", "answer_yes", "answer_no"})
            assertThrows(IllegalArgumentException.class, () -> session.select(forbidden, true, true));
    }
    @Test void questionMustStillBePendingToAcceptPermissionAndWhyReplyHasNoAnswer() {
        begin();
        assertThrows(IllegalArgumentException.class, () -> session.select("answer_yes", true, false));
        assertEquals(DialogueSession.Effect.YES, session.select("answer_yes", true, true));
        begin();
        session.select("consult_dialogue", true, true);
        session.generated(session.request().id(), new DeepSeekClient.Reply("因为房子没有出口", null, "keep", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> session.select("answer_yes", true, true));
        assertEquals(DialogueSession.Effect.SEND, session.select("send_reply", true, true));
    }
    @Test void incompleteUnderstandingCanBeCorrectedOnlyOnceAndOnlyForPlayerDialogue() {
        begin();
        session.select("consult_dialogue", true, false);
        long id = session.request().id();
        session.generated(id, new DeepSeekClient.Reply("我这就去", null, "keep", "", 0, 0));
        assertEquals(DialogueSession.Effect.GENERATE, session.select("clarify_understanding", true, false));
        session.generated(id, new DeepSeekClient.Reply("我这就去", null, "keep", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> session.select("clarify_understanding", true, false));
        session.begin(DialogueSession.Mode.COMPOSE_SPEECH, "已到达", "goal_result", "g1");
        session.select("consult_dialogue", true, false);
        session.generated(session.request().id(), new DeepSeekClient.Reply("已到达", null, "keep", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> session.select("clarify_understanding", true, false));
    }

    @Test void uncertaintyDistinguishesLanguagePreferenceFromConsequentialChoices() {
        begin();
        assertEquals(0, session.minimumConfidence("consult_dialogue", 0.35));
        assertEquals(0.35, session.minimumConfidence("adopt_plan", 0.35));
        assertEquals(0.5, session.minimumConfidence("answer_yes", 0.35));
        assertEquals(0.7, session.minimumConfidence("drop_message", 0.35));
        session.begin(DialogueSession.Mode.COMPOSE_SPEECH, "可以挖墙吗", "permission_question", "g1");
        assertEquals(0.7, session.minimumConfidence("drop_message", 0.35));
        session.begin(DialogueSession.Mode.COMPOSE_SPEECH, "天黑了", "nightfall", "g1");
        assertEquals(0, session.minimumConfidence("drop_message", 0.35), "optional notices may be ignored without repeated calls");
    }

    @Test void unavailableLanguageServiceCannotBeSelected() {
        begin();
        assertThrows(IllegalArgumentException.class, () -> session.select("consult_dialogue", false, false));
    }

    @Test void providerFailureRequiresJevToRetryAndKeepsTheSingleRepairBudget() {
        begin();
        session.select("consult_dialogue", true, false);
        long id = session.request().id();
        var failure = new DeepSeekClient.Reply("交流失败，当前任务保持不变。", null, "keep", "", 0, 0);
        session.generated(id, failure, "INVALID_RESPONSE");
        assertEquals(DialogueSession.Phase.REVIEW, session.phase(), "failure must return to Jev, not auto-retry");
        assertEquals("INVALID_RESPONSE", session.state().get("generation_error").getAsString());
        assertEquals(0, session.minimumConfidence("send_reply", 0.35), "a factual provider-error notice needs no confidence in language interpretation");
        assertThrows(IllegalArgumentException.class, () -> session.select("adopt_plan", true, false));
        assertEquals(DialogueSession.Effect.GENERATE, session.select("clarify_understanding", true, false));
        session.generated(id, failure, "INVALID_RESPONSE");
        assertThrows(IllegalArgumentException.class, () -> session.select("clarify_understanding", true, false));
        assertEquals(DialogueSession.Effect.SEND, session.select("send_reply", true, false));
        begin();
        assertFalse(session.state().has("generation_error"), "provider failures must not leak into the next message");
    }

    @Test void onlyJevCanPauseForARevisionAndAllTerminalOutcomesReleaseIt() {
        begin();
        assertFalse(session.pausesWork(), "receipt of player text alone must never pause");
        assertEquals(DialogueSession.Effect.GENERATE, session.select("consult_and_pause", true, false));
        assertTrue(session.pausesWork());
        session.generated(session.request().id(), proposal());
        assertTrue(session.pausesWork(), "keep progress frozen through review");
        session.select("reject_plan", true, false);
        assertFalse(session.pausesWork(), "rejected revision resumes original work");
        begin(); session.select("consult_and_pause", true, false); session.clear();
        assertFalse(session.pausesWork(), "cancellation/failure releases the pause");
        session.begin(DialogueSession.Mode.UNDERSTAND_PLAYER, "闲聊", "chat", "none:initial");
        assertThrows(IllegalArgumentException.class, () -> session.select("consult_and_pause", true, false));
        session.select("consult_dialogue", true, false);
        assertFalse(session.pausesWork(), "ordinary chat keeps the body moving");
    }
}
