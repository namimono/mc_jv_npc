package dev.jev.npc.trace;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import static dev.jev.npc.trace.TraceRecorder.data;
import static org.junit.jupiter.api.Assertions.*;

class TraceRecorderTest {
    @TempDir Path directory;

    @Test void redactsBothDirectionsPreservesLinksAndEscapesHtmlInEveryArchive() throws Exception {
        Path page;
        var warnings = new ArrayList<String>();
        try (var recorder = new TraceRecorder(directory, warnings::add, 3)) {
            page = recorder.page();
            recorder.secret("secret-test-123");
            var root = recorder.root("dialogue", data("npc", "npc-1"), data("text", "</script><script>window.pwned=true</script>"));
            var call = root.child("jev", data("purpose", "route"));
            call.event("request", data("input", data("message", "key secret-test-123", "api_key", "another-value")));
            call.response("{\"authorization\":\"Bearer hidden-token\",\"answer\":\"secret-test-123\"}", 200, 42);
            call.event("applied", data("outcome", "consult_dialogue"));
        }
        var rows = rows(page);
        assertEquals(5, rows.size());
        assertEquals(rows.getFirst().get("span"), rows.get(1).get("parent"));
        assertEquals(rows.get(1).get("span"), rows.get(3).get("span"));
        assertEquals("npc-1", rows.get(3).getAsJsonObject("tags").get("npc").getAsString());
        assertTrue(Files.exists(page.resolveSibling("part-0002.html")));
        for (Path file : Files.list(page.getParent()).toList()) {
            String text = Files.readString(file);
            assertFalse(text.contains("secret-test-123"));
            assertFalse(text.contains("another-value"));
            assertFalse(text.contains("hidden-token"));
            assertFalse(text.contains("<script>window.pwned"));
        }
        assertTrue(Files.readString(page).contains("\"live\":false"));
        assertTrue(warnings.isEmpty());
    }

    @Test void concurrentCallbacksKeepSequenceOrderAndCloseDrains() throws Exception {
        Path page;
        try (var recorder = new TraceRecorder(directory, message -> fail(message))) {
            page = recorder.page();
            var root = recorder.root("goal", data("goal", "g-1"), data());
            try (var pool = Executors.newFixedThreadPool(4)) {
                for (int i=0;i<200;i++) {
                    int value=i;
                    pool.submit(() -> root.event("progress", data("count", value)));
                }
            }
        }
        var events=rows(page);
        assertEquals(201, events.size());
        for (int i=0;i<events.size();i++) assertEquals(i+1,events.get(i).get("seq").getAsInt());
    }

    @Test void capturesImmutableSnapshotsAndMarksOversizedStrings() throws Exception {
        Path page;
        try (var recorder = new TraceRecorder(directory, message -> fail(message))) {
            page=recorder.page();
            var input=data("text", "a".repeat(530000));
            recorder.root("event", data(), input);
            input.addProperty("text", "changed after submission");
        }
        String value=rows(page).getFirst().getAsJsonObject("data").get("text").getAsString();
        assertTrue(value.startsWith("aaaa"));
        assertTrue(value.contains("TRUNCATED"));
    }

    private static List<JsonObject> rows(Path page) throws Exception {
        return Files.readAllLines(page.resolveSibling("events.jsonl")).stream().map(line->JsonParser.parseString(line).getAsJsonObject()).toList();
    }
}
