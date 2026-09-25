package dev.jev.npc.ai;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class CommunicatorTest {
    private final List<String> sent = new ArrayList<>();
    private final Communicator speech = new Communicator((channel, text) -> sent.add(channel + ":" + text));

    @Test void identicalReportsAreDeduplicatedForAFewSeconds() {
        assertTrue(speech.report("开始采集", 0));
        assertFalse(speech.report("开始采集", 50));
        assertTrue(speech.report("采集完成", 50));
        assertTrue(speech.report("开始采集", Communicator.REPORT_DEDUP_TICKS));
        assertEquals(List.of("REPORT:开始采集", "REPORT:采集完成", "REPORT:开始采集"), sent);
    }

    @Test void remarksRespectTopicCooldownAndGlobalGap() {
        assertTrue(speech.remark("night", "天快黑了", 12000, 0));
        assertFalse(speech.remark("hurt", "我受伤了", 1200, 100), "global gap between remarks");
        assertTrue(speech.remark("hurt", "我受伤了", 1200, Communicator.REMARK_GAP_TICKS));
        assertFalse(speech.remark("night", "天快黑了", 12000, 5000), "topic still cooling down");
        assertTrue(speech.remark("night", "天快黑了", 12000, 12000));
    }

    @Test void questionExpiresToFallbackAndStopsExpectingReply() {
        var question = new Communicator.Question("risk", "要冒险吗？", Communicator.yesNo("同意", "拒绝"), "no", 1000);
        speech.ask(question, 0);
        assertEquals(List.of("TO_OWNER:要冒险吗？"), sent);
        assertTrue(speech.expectsReply(900));
        assertEquals(Optional.empty(), speech.expire(999));
        assertEquals(Optional.of("no"), speech.expire(1000));
        assertTrue(speech.pending().isEmpty());
        assertFalse(speech.expectsReply(1000 + Communicator.CONVERSATION_TICKS));
    }

    @Test void recentSpeechOpensAShortConversationWindow() {
        assertFalse(speech.expectsReply(0));
        speech.say("你好", 10);
        assertTrue(speech.expectsReply(10 + Communicator.CONVERSATION_TICKS));
        assertFalse(speech.expectsReply(11 + Communicator.CONVERSATION_TICKS));
    }

    @Test void refusalKeywordsWinOverAgreementInsideThem() {
        assertEquals(Optional.of("no"), Communicator.interpret("不可以"));
        assertEquals(Optional.of("no"), Communicator.interpret("别挖，不好"));
        assertEquals(Optional.of("no"), Communicator.interpret("不"));
        assertEquals(Optional.of("yes"), Communicator.interpret("可以，去吧"));
        assertEquals(Optional.of("yes"), Communicator.interpret("OK"));
        assertEquals(Optional.empty(), Communicator.interpret("先去砍树"));
    }
}
