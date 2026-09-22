package dev.jev.npc.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DecisionSchedulingTest {
    @Test void newerCommandInvalidatesReplyAndPreservesSingleFlight() {
        DecisionGate gate = new DecisionGate();
        var ticket = gate.begin(100).orElseThrow();
        gate.invalidate();
        assertTrue(gate.begin(120).isEmpty(), "must not flood server while a cancelled context is in flight");
        assertFalse(gate.complete(ticket, 150, 1000));
        var fresh = gate.begin(160).orElseThrow();
        assertTrue(gate.complete(fresh, 200, 1000));
    }

    @Test void expiredResultIsRejectedAndGateReopens() {
        DecisionGate gate = new DecisionGate();
        var ticket = gate.begin(0).orElseThrow();
        assertFalse(gate.complete(ticket, 1001, 1000));
        assertFalse(gate.inFlight());
    }

    @Test void duplicateCallbackCannotReleaseAnotherRequest() {
        DecisionGate gate = new DecisionGate();
        var old = gate.begin(0).orElseThrow();
        assertTrue(gate.complete(old, 20, 1000));
        var current = gate.begin(30).orElseThrow();
        assertFalse(gate.complete(old, 40, 1000));
        assertTrue(gate.inFlight());
        assertTrue(gate.complete(current, 50, 1000));
    }

    @Test void repeatedDamageIsCoalescedWithoutStarvingDebounce() {
        EventInbox inbox = new EventInbox();
        inbox.add("damage", "first", 10);
        inbox.add("damage", "latest", 17);
        inbox.add("chat", "help", 17);
        assertFalse(inbox.ready(17, 8));
        assertTrue(inbox.ready(18, 8));
        var events = inbox.drain();
        assertEquals(2, events.size());
        assertEquals("latest", events.get("damage"));
        assertFalse(inbox.ready(100, 8));
    }

    @Test void eventBacklogIsBounded() {
        EventInbox inbox = new EventInbox();
        for (int i = 0; i < 100; i++) inbox.add("event_" + i, "value", i);
        assertEquals(12, inbox.drain().size());
    }

    @Test void budgetUsesSlidingWindowSharedAcrossNpcCalls() {
        RequestBudget budget = new RequestBudget();
        assertTrue(budget.acquire(0, 2));
        assertTrue(budget.acquire(500, 2));
        assertFalse(budget.acquire(59_999, 2));
        assertTrue(budget.acquire(60_000, 2));
        assertFalse(budget.acquire(60_001, 2));
        assertTrue(budget.acquire(60_500, 2));
    }
}
