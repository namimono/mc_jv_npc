package dev.jev.npc.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.jev.npc.ai.JevClient.JevFailure;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * DeepSeek chat completions in JSON Output mode. Only messages go out; a sanitized reply and validated
 * {@link GoalPlan} proposal come back. The caller must submit the proposal to Jev before applying it.
 */
public final class DeepSeekClient implements AutoCloseable {
    public static final URI OFFICIAL_ENDPOINT = URI.create("https://api.deepseek.com/chat/completions");
    static final int MAX_REPLY_CHARS = 200;
    private static final Gson GSON = new Gson();

    public record Message(String role, String content) {}
    public record Reply(String say, GoalPlan plan, String change, String answer, int promptTokens, long elapsedMs) {
        public boolean hasTask() { return plan != null && (change.equals("replace") || change.equals("amend")); }
        public Reply speechOnly() { return new Reply(say, null, "keep", "", promptTokens, elapsedMs); }
        public JsonObject json() {
            JsonObject value = new JsonObject();
            value.addProperty("reply", say);
            value.addProperty("goal_change", change);
            value.addProperty("answer", answer);
            value.add("plan", plan == null ? null : plan.json());
            return value;
        }
    }

    private final HttpClient http;
    private final URI endpoint;

    public DeepSeekClient() { this(OFFICIAL_ENDPOINT); }

    // Package-private endpoint injection is for local HTTP tests, not user configuration.
    DeepSeekClient(URI endpoint) {
        this.endpoint = endpoint;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public CompletableFuture<Reply> chat(String key, String model, List<Message> messages, int timeoutMs) {
        return chat(key, model, messages, timeoutMs, DialogueSession.Mode.UNDERSTAND_PLAYER);
    }

    public CompletableFuture<Reply> chat(String key, String model, List<Message> messages, int timeoutMs, DialogueSession.Mode mode) {
        if (key == null || key.isBlank()) return CompletableFuture.failedFuture(new JevFailure("MISSING_KEY"));
        if (key.chars().anyMatch(character -> character <= 32 || character >= 127))
            return CompletableFuture.failedFuture(new JevFailure("INVALID_KEY_FORMAT"));
        long started = System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(timeoutMs))
            .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload(model, messages)))).build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .handle((response, failure) -> {
                if (failure != null) throw new CompletionException(new JevFailure("NETWORK_OR_TIMEOUT"));
                // Never log response body or headers: providers may echo inputs.
                if (response.statusCode() != 200) throw new CompletionException(new JevFailure("HTTP_" + response.statusCode()));
                return parse(response.body(), (System.nanoTime() - started) / 1_000_000, mode);
            });
    }

    static JsonObject payload(String model, List<Message> messages) {
        JsonArray list = new JsonArray();
        for (Message message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            item.addProperty("content", message.content());
            list.add(item);
        }
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_object");
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", "disabled");
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.add("messages", list);
        payload.add("response_format", format);
        payload.add("thinking", thinking);
        payload.addProperty("max_tokens", 1800);
        payload.addProperty("temperature", 0.3);
        payload.addProperty("stream", false);
        return payload;
    }

    static Reply parse(String body, long elapsedMs) {
        return parse(body, elapsedMs, DialogueSession.Mode.UNDERSTAND_PLAYER);
    }

    static Reply parse(String body, long elapsedMs, DialogueSession.Mode mode) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String content = root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
            JsonObject answer = JsonParser.parseString(content).getAsJsonObject();
            String say = clean(text(answer, "reply"), MAX_REPLY_CHARS);
            String change = text(answer, "goal_change");
            String permission = text(answer, "answer");
            GoalPlan plan = null;
            if (mode == DialogueSession.Mode.COMPOSE_SPEECH) {
                change = "keep";
                permission = "";
            } else {
                if (!java.util.Set.of("keep", "replace", "amend", "cancel").contains(change)) throw new IllegalArgumentException();
                if (!java.util.Set.of("", "yes", "no").contains(permission)) throw new IllegalArgumentException();
                if (change.equals("replace") || change.equals("amend")) plan = GoalPlan.parse(answer.getAsJsonObject("plan"));
            }
            if (say.isBlank() && plan == null && permission.isBlank() && !change.equals("cancel")) throw new IllegalArgumentException();
            int tokens = root.has("usage") && root.getAsJsonObject("usage").has("prompt_tokens")
                ? root.getAsJsonObject("usage").get("prompt_tokens").getAsInt() : 0;
            return new Reply(say, plan, change, permission, Math.max(0, tokens), elapsedMs);
        } catch (RuntimeException exception) { throw new JevFailure("INVALID_RESPONSE"); }
    }

    private static String text(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }

    /** One chat line: no control characters, no formatting codes, bounded length. */
    static String clean(String text, int limit) {
        String line = text.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").replace("§", "").trim();
        return line.length() > limit ? line.substring(0, limit) : line;
    }

    @Override public void close() { http.shutdownNow(); }
}
