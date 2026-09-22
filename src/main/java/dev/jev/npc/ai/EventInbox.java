package dev.jev.npc.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/** Coalesces repeated events without resetting the first event's debounce deadline. */
public final class EventInbox {
    private final LinkedHashMap<String, String> events = new LinkedHashMap<>();
    private long firstTick;

    public void add(String kind, String detail, long tick) {
        if (events.isEmpty()) firstTick = tick;
        events.put(kind, detail);
        if (events.size() > 12) events.remove(events.keySet().iterator().next());
    }

    public boolean ready(long tick, int debounceTicks) {
        return !events.isEmpty() && tick - firstTick >= debounceTicks;
    }

    public Map<String, String> drain() {
        Map<String, String> result = new LinkedHashMap<>(events);
        events.clear();
        return result;
    }
}
