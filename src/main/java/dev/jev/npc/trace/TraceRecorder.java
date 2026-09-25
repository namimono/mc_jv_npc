package dev.jev.npc.trace;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Append-only diagnostic side channel. No Minecraft objects or HTTP headers enter this module. */
public final class TraceRecorder implements AutoCloseable {
    private static final Gson JSON = new Gson(); // HTML escaping intentionally enabled.
    private final Path directory;
    private final String session = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID().toString().substring(0, 8);
    private final ArrayBlockingQueue<JsonObject> queue = new ArrayBlockingQueue<>(1024);
    private final List<String> secrets = new CopyOnWriteArrayList<>();
    private final Consumer<String> warning;
    private final Thread writer;
    private final String template;
    private final int segmentLimit;
    private long sequence, dropped;
    private volatile boolean closed, failed;
    private volatile String problem = "";

    public TraceRecorder(Path root, Consumer<String> warning) throws IOException { this(root, warning, 500); }
    TraceRecorder(Path root, Consumer<String> warning, int segmentLimit) throws IOException {
        this.warning = warning;
        this.segmentLimit = segmentLimit;
        directory = root.resolve(session);
        Files.createDirectories(directory);
        try (var stream = TraceRecorder.class.getResourceAsStream("/jev-trace/viewer.html")) {
            if (stream == null) throw new IOException("Missing trace viewer resource");
            template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        render(List.of(), 1, true);
        writer = new Thread(this::writeLoop, "jev-trace-writer");
        writer.setDaemon(true);
        writer.start();
    }

    public Path page() { return directory.resolve("index.html").toAbsolutePath(); }
    public String problem() { return problem; }
    public boolean available() { return !closed && !failed; }
    public void secret(String secret) {
        if (secret != null && !secret.isBlank() && !secrets.contains(secret)) secrets.add(secret);
    }
    public Span root(String kind, JsonObject tags, JsonObject data) { return begin("", kind, tags, data); }
    private Span begin(String parent, String kind, JsonObject tags, JsonObject data) {
        Span span = new Span(this, UUID.randomUUID().toString(), parent, kind, tags.deepCopy());
        span.event("start", data);
        return span;
    }

    private synchronized void record(Span span, String phase, JsonObject data) {
        if (!available()) return;
        JsonObject event = new JsonObject();
        event.addProperty("schema", 1);
        event.addProperty("session", session);
        event.addProperty("seq", ++sequence);
        event.addProperty("time", Instant.now().toString());
        event.addProperty("span", span.id);
        event.addProperty("parent", span.parent);
        event.addProperty("kind", span.kind);
        event.addProperty("phase", phase);
        event.add("tags", span.tags.deepCopy());
        event.add("data", data.deepCopy());
        // Missing sequence numbers are intentional and explained on every generated page.
        if (!queue.offer(event)) {
            dropped++;
            if (dropped == 1) warning.accept("Trace queue full; diagnostic events dropped. Gameplay continues.");
        }
    }

    private void writeLoop() {
        List<JsonObject> segment = new ArrayList<>();
        int part = 1, bytes = 0;
        long rendered = 0;
        boolean dirty = false, rotatePending = false;
        try (BufferedWriter output = Files.newBufferedWriter(directory.resolve("events.jsonl"), StandardCharsets.UTF_8)) {
            while (!closed || !queue.isEmpty()) {
                JsonObject event = queue.poll(250, TimeUnit.MILLISECONDS);
                if (event != null) {
                    if (rotatePending) { segment.clear(); bytes = 0; part++; rotatePending = false; }
                    event = sanitize(event).getAsJsonObject();
                    String line = JSON.toJson(event);
                    output.write(line);
                    output.newLine();
                    segment.add(event);
                    dirty = true;
                    bytes += line.length();
                }
                boolean rotate = dirty && (segment.size() >= segmentLimit || bytes >= 4_000_000);
                if (rotate || dirty && System.nanoTime() - rendered >= 1_000_000_000L || closed) {
                    output.flush();
                    render(segment, part, !closed || !queue.isEmpty());
                    rendered = System.nanoTime();
                    dirty = false;
                }
                if (rotate) rotatePending = true;
            }
        } catch (IOException | RuntimeException | InterruptedException error) {
            failed = true;
            problem = "Trace writing failed (" + error.getClass().getSimpleName() + "); recording stopped.";
            warning.accept(problem); // Do not echo paths or exception text containing provider data.
        }
    }

    private synchronized long droppedCount() { return dropped; }
    private void render(List<JsonObject> events, int part, boolean live) throws IOException {
        JsonObject model = data("session", session, "part", part, "live", live, "dropped", droppedCount());
        model.add("events", JSON.toJsonTree(events));
        String page = template.replace("/*TRACE_DATA*/{}", JSON.toJson(model));
        atomicWrite(directory.resolve("index.html"), page);
        model.addProperty("live", false);
        atomicWrite(directory.resolve(String.format("part-%04d.html", part)), template.replace("/*TRACE_DATA*/{}", JSON.toJson(model)));
    }

    private static void atomicWrite(Path path, String body) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp, body, StandardCharsets.UTF_8);
        try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
    }

    private JsonElement sanitize(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            value.getAsJsonObject().entrySet().forEach(entry -> result.add(entry.getKey(),
                entry.getKey().replaceAll("[^a-zA-Z]", "").toLowerCase(java.util.Locale.ROOT)
                    .matches(".*(apikey|authorization|password|secret|accesstoken|refreshtoken).*" )
                    ? JSON.toJsonTree("[REDACTED]") : sanitize(entry.getValue())));
            return result;
        }
        if (value.isJsonArray()) {
            var result = new com.google.gson.JsonArray();
            value.getAsJsonArray().forEach(item -> result.add(sanitize(item)));
            return result;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String text = value.getAsString();
            for (String secret : secrets) text = text.replace(secret, "[REDACTED]");
            text = text.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]");
            if (text.length() > 524288) text = text.substring(0, 524288) + "\n[TRUNCATED: diagnostic string exceeded 524288 characters]";
            return JSON.toJsonTree(text);
        }
        return value;
    }

    public static JsonObject data(Object... fields) {
        JsonObject data = new JsonObject();
        for (int i = 0; i < fields.length; i += 2) data.add(fields[i].toString(), JSON.toJsonTree(fields[i + 1]));
        return data;
    }

    @Override public void close() {
        synchronized (this) { closed = true; }
        try { writer.join(5000); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        if (writer.isAlive()) {
            problem = "Trace shutdown timed out; queued events may be missing.";
            warning.accept(problem);
        }
    }

    public static final class Span {
        public static final Span NONE = new Span(null, "", "", "", new JsonObject());
        private final TraceRecorder recorder;
        private final String id, parent, kind;
        private final JsonObject tags;
        private Span(TraceRecorder recorder, String id, String parent, String kind, JsonObject tags) {
            this.recorder = recorder; this.id = id; this.parent = parent; this.kind = kind; this.tags = tags;
        }
        public String id() { return id; }
        public boolean belongsTo(TraceRecorder current) { return recorder == current && current != null && current.available(); }
        public void secret(String value) { if (recorder != null) recorder.secret(value); }
        public Span child(String kind, JsonObject data, Object... extraTags) {
            if (recorder == null) return NONE;
            JsonObject merged = tags.deepCopy();
            data(extraTags).entrySet().forEach(entry -> merged.add(entry.getKey(), entry.getValue()));
            return recorder.begin(id, kind, merged, data);
        }
        public void event(String phase, JsonObject data) { if (recorder != null) recorder.record(this, phase, data); }
        /** Preserve invalid provider bodies too; the writer redacts registered credentials before persistence. */
        public void response(String body, int status, long elapsed) {
            JsonElement output;
            try { output = JsonParser.parseString(body); }
            catch (RuntimeException invalid) { output = JSON.toJsonTree(body); }
            event("response", data("http_status", status, "elapsed_ms", elapsed, "output", output));
        }
    }
}
