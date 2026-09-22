package dev.jev.npc.ai;

import java.util.Optional;

/** Called only by the game thread. Late replies cannot override a newer command. */
public final class DecisionGate {
    public record Ticket(long id, long revision, long startedAtMs) {}
    private long sequence;
    private long revision;
    private Ticket active;

    public Optional<Ticket> begin(long nowMs) {
        if (active != null) return Optional.empty();
        active = new Ticket(++sequence, revision, nowMs);
        return Optional.of(active);
    }

    public boolean complete(Ticket ticket, long nowMs, long maxAgeMs) {
        if (active == null || active.id() != ticket.id()) return false;
        active = null;
        long elapsed = nowMs - ticket.startedAtMs();
        return ticket.revision() == revision && elapsed >= 0 && elapsed <= maxAgeMs;
    }

    public void invalidate() { revision++; }
    public boolean inFlight() { return active != null; }
}
