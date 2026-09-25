package dev.jev.npc.ai;

import java.util.HashMap;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The single outlet for everything the NPC says. Reports are deduplicated, remarks have per-topic cooldowns and a global
 * gap, and at most one question is open. Time is passed in as game ticks so the rules are testable without a world.
 */
public final class Communicator {
    /** REPORT: status line to the owner. TO_OWNER: chat-styled line to the owner. NEARBY: chat line to nearby players. */
    public enum Channel { REPORT, TO_OWNER, NEARBY }
    public interface Sink { void send(Channel channel, String text); }

    /** {@code options} maps answer ids to their meaning; {@code fallback} is applied when nobody answers in time. */
    public record Question(String kind, String prompt, Map<String, String> options, String fallback, long expiresAt) {}

    static final int REPORT_DEDUP_TICKS = 100;
    static final int REMARK_GAP_TICKS = 200;
    static final int CONVERSATION_TICKS = 600;
    private static final List<String> NO = List.of("不要", "不行", "不可以", "不能", "不好", "不用", "不了", "不是", "没必要", "算了",
        "别", "拒绝", "取消", "no", "nope", "don't", "dont");
    private static final List<String> YES = List.of("可以", "好", "行", "同意", "允许", "去吧", "冒险", "挖吧", "没问题", "是",
        "嗯", "ok", "yes", "sure");

    private final Sink sink;
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private final Map<String, Long> reports = new HashMap<>();
    private final Map<String, Long> topics = new HashMap<>();
    private long lastRemark = Long.MIN_VALUE / 2;
    private long lastToOwner = Long.MIN_VALUE / 2;
    private Question pending;

    public Communicator(Sink sink) { this.sink = sink; }

    public boolean report(String text, long tick) {
        Long last = reports.get(text);
        if (last != null && tick - last < REPORT_DEDUP_TICKS) return false;
        reports.put(text, tick);
        if (reports.size() > 32) reports.values().removeIf(sent -> tick - sent >= REPORT_DEDUP_TICKS);
        send(Channel.REPORT, text);
        return true;
    }

    /** A proactive observation; dropped while its topic is cooling down or another remark was just made. */
    public boolean remark(String topic, String text, int cooldownTicks, long tick) {
        Long last = topics.get(topic);
        if (last != null && tick - last < cooldownTicks || tick - lastRemark < REMARK_GAP_TICKS) return false;
        topics.put(topic, tick);
        lastRemark = tick;
        lastToOwner = tick;
        send(Channel.NEARBY, text);
        return true;
    }

    /** Conversational speech that must not be dropped, e.g. a reply to the owner. */
    public void say(String text, long tick) {
        lastToOwner = tick;
        send(Channel.NEARBY, text);
    }

    public void tell(String text, long tick) {
        lastToOwner = tick;
        send(Channel.TO_OWNER, text);
    }

    private void send(Channel channel, String text) {
        sink.send(channel, text);
        recent.addLast(text);
        while (recent.size() > 8) recent.removeFirst();
    }

    /** Only lines actually sent, including observations and permission questions. */
    public List<String> recent() { return List.copyOf(recent); }

    public void open(Question question) { pending = question; }

    public void ask(Question question, long tick) {
        pending = question;
        tell(question.prompt(), tick);
    }

    public Optional<Question> pending() { return Optional.ofNullable(pending); }
    public boolean canAnswer(Question expected, long tick) {
        return expected != null && pending == expected && tick < expected.expiresAt();
    }
    public void resolve() { pending = null; }

    /** The fallback answer once the open question has expired; the question is closed. */
    public Optional<String> expire(long tick) {
        if (pending == null || tick < pending.expiresAt()) return Optional.empty();
        String fallback = pending.fallback();
        pending = null;
        return Optional.of(fallback);
    }

    /** Whether an undirected chat line from the owner should be read as a reply. */
    public boolean expectsReply(long tick) { return pending != null || tick - lastToOwner <= CONVERSATION_TICKS; }

    /** Yes/no from plain keywords; refusals are checked first so that 不可以 is not read as 可以. */
    public static Optional<String> interpret(String reply) {
        String text = reply.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return Optional.empty();
        if (text.equals("不") || NO.stream().anyMatch(text::contains)) return Optional.of("no");
        if (YES.stream().anyMatch(text::contains)) return Optional.of("yes");
        return Optional.empty();
    }

    public static Map<String, String> yesNo(String yes, String no) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("yes", yes);
        options.put("no", no);
        return options;
    }
}
