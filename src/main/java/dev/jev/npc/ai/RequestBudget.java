package dev.jev.npc.ai;

import java.util.ArrayDeque;

/** One budget shared by every NPC on this Minecraft server. */
public final class RequestBudget {
    private final ArrayDeque<Long> requests = new ArrayDeque<>();

    public boolean acquire(long nowMs, int perMinute) {
        while (!requests.isEmpty() && nowMs - requests.peekFirst() >= 60_000) requests.removeFirst();
        if (requests.size() >= perMinute) return false;
        requests.addLast(nowMs);
        return true;
    }

    public void clear() { requests.clear(); }
}
