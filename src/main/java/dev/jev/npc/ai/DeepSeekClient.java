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
 * DeepSeek chat completions in JSON Output mode. Only messages go out; only a sanitized reply and a validated
 * {@link GoalIntent} come back, so the conversation model can never reach past the NPC's existing capabilities.
 */
public final class DeepSeekClient implements AutoCloseable {
    public static final URI OFFICIAL_ENDPOINT = URI.create("https://api.deepseek.com/chat/completions");
    static final int MAX_REPLY_CHARS = 200;
    static final int MAX_REQUEST_CHARS = 120;
    private static final Gson GSON = new Gson();

    public record Message(String role, String content) {}
    /** {@code taskRequest} is empty when the owner was only chatting; {@code intent} is null unless fully valid. */
    public record Reply(String say, String taskRequest, GoalIntent intent, int promptTokens, long elapsedMs) {
        public boolean hasTask() { return intent != null || !taskRequest.isEmpty(); }
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
                return parse(response.body(), (System.nanoTime() - started) / 1_000_000);
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
        payload.addProperty("max_tokens", 600);
        payload.addProperty("temperature", 0.8);
        payload.addProperty("stream", false);
        return payload;
    }

    static Reply parse(String body, long elapsedMs) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String content = root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
            JsonObject answer = JsonParser.parseString(content).getAsJsonObject();
            String say = clean(text(answer, "reply"), MAX_REPLY_CHARS);
            boolean task = "task".equals(text(answer, "action"));
            GoalIntent intent = task ? intent(answer.get("intent")) : null;
            String request = task ? clean(text(answer, "task_request"), MAX_REQUEST_CHARS) : "";
            int tokens = root.has("usage") && root.getAsJsonObject("usage").has("prompt_tokens")
                ? root.getAsJsonObject("usage").get("prompt_tokens").getAsInt() : 0;
            return new Reply(say, request, intent, Math.max(0, tokens), elapsedMs);
        } catch (RuntimeException exception) { throw new JevFailure("INVALID_RESPONSE"); }
    }

    private static GoalIntent intent(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject intent = element.getAsJsonObject();
        int amount = intent.has("amount") && intent.get("amount").isJsonPrimitive() && intent.get("amount").getAsJsonPrimitive().isNumber()
            ? intent.get("amount").getAsInt() : 0;
        boolean deliver = intent.has("deliver_to_owner") && intent.get("deliver_to_owner").isJsonPrimitive()
            && intent.get("deliver_to_owner").getAsJsonPrimitive().isBoolean() && intent.get("deliver_to_owner").getAsBoolean();
        return GoalIntent.validated(text(intent, "verb"), text(intent, "material"), text(intent, "place"), amount, deliver).orElse(null);
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
