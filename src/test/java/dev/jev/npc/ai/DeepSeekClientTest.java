package dev.jev.npc.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekClientTest {
    private HttpServer server;
    private DeepSeekClient client;

    @AfterEach void cleanup() { if (client != null) client.close(); if (server != null) server.stop(0); }

    private static String completion(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        choice.addProperty("finish_reason", "stop");
        JsonObject root = new JsonObject();
        root.add("choices", new com.google.gson.JsonArray());
        root.getAsJsonArray("choices").add(choice);
        root.addProperty("model", "deepseek-flash");
        JsonObject usage = new JsonObject();
        usage.addProperty("prompt_tokens", 321);
        root.add("usage", usage);
        return root.toString();
    }

    private void serve(int status, String body, AtomicReference<String> auth, AtomicReference<JsonObject> payload) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            if (auth != null) auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (payload != null) payload.set(JsonParser.parseString(request).getAsJsonObject());
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        client = new DeepSeekClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"));
    }

    @Test void sendsJsonModeWithoutThinkingAndReadsAValidatedGoal() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<JsonObject> payload = new AtomicReference<>();
        serve(200, completion("""
            {"reply":"好嘞，我去砍几块木头给你。","action":"task","task_request":"砍四块原木交给主人",
             "intent":{"verb":"harvest","material":"log","place":"owner","amount":4,"deliver_to_owner":true}}
            """), auth, payload);
        var messages = new Conversation().messages("谨慎", new JsonObject(), "帮我砍点木头");
        var reply = client.chat("ds-test-key", "deepseek-flash", messages, 2000).get(3, TimeUnit.SECONDS);
        assertEquals("Bearer ds-test-key", auth.get());
        assertEquals("json_object", payload.get().getAsJsonObject("response_format").get("type").getAsString());
        assertEquals("disabled", payload.get().getAsJsonObject("thinking").get("type").getAsString());
        assertEquals("system", payload.get().getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
        assertTrue(payload.get().getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("json"),
            "JSON Output requires the word json in the prompt");
        assertEquals("好嘞，我去砍几块木头给你。", reply.say());
        assertEquals(new GoalIntent("harvest", "log", "owner", 4, true), reply.intent());
        assertEquals(321, reply.promptTokens());
    }

    @Test void chatOnlyRepliesCarryNoGoal() {
        var reply = DeepSeekClient.parse(completion("{\"reply\":\"今天天气不错。\",\"action\":\"none\",\"intent\":null}"), 5);
        assertFalse(reply.hasTask());
        assertEquals("今天天气不错。", reply.say());
    }

    @Test void inventedIntentFallsBackToTheRestatedRequestForJev() {
        var reply = DeepSeekClient.parse(completion("""
            {"reply":"好的","action":"task","task_request":"挖十块钻石",
             "intent":{"verb":"mine","material":"diamond","amount":10}}
            """), 5);
        assertNull(reply.intent(), "unsupported material and amount must not become a goal");
        assertEquals("挖十块钻石", reply.taskRequest());
        assertTrue(reply.hasTask());
    }

    @Test void replyIsOneBoundedChatLineWithoutFormattingCodes() {
        String longText = "§c第一行\n第二行" + "很".repeat(400);
        JsonObject answer = new JsonObject();
        answer.addProperty("reply", longText);
        answer.addProperty("action", "none");
        var reply = DeepSeekClient.parse(completion(answer.toString()), 5);
        assertFalse(reply.say().contains("\n") || reply.say().contains("§"));
        assertEquals(DeepSeekClient.MAX_REPLY_CHARS, reply.say().length());
    }

    @Test void httpErrorsDoNotExposeTheBody() throws Exception {
        serve(401, "secret-echo-of-input", null, null);
        var error = assertThrows(CompletionException.class,
            () -> client.chat("ds-test-key", "deepseek-flash", List.of(new DeepSeekClient.Message("user", "hi")), 2000).join());
        assertEquals("HTTP_401", JevClient.errorCode(error));
        assertFalse(error.toString().contains("secret-echo"));
    }

    @Test void nonJsonContentAndMissingKeyAreRejected() {
        assertThrows(JevClient.JevFailure.class, () -> DeepSeekClient.parse(completion("不是 json"), 5));
        client = new DeepSeekClient();
        var error = assertThrows(CompletionException.class,
            () -> client.chat("", "deepseek-flash", List.of(new DeepSeekClient.Message("user", "hi")), 2000).join());
        assertEquals("MISSING_KEY", JevClient.errorCode(error));
    }

    @Test void conversationKeepsOnlyRecentTurns() {
        var conversation = new Conversation();
        for (int i = 0; i < 10; i++) conversation.record("问题" + i, "回答" + i);
        var messages = conversation.messages("谨慎", new JsonObject(), "最新");
        assertEquals(1 + Conversation.MAX_MESSAGES + 1, messages.size());
        assertEquals("问题6", messages.get(1).content());
        assertEquals("最新", messages.getLast().content());
    }

    @Test void goalIntentValidationFollowsVerbSpecificFields() {
        assertEquals(new GoalIntent("mine", "ground", "owner", 1, false), GoalIntent.validated("mine", "ground", null, 0, false).orElseThrow());
        assertEquals(4, GoalIntent.validated("harvest", "", "", 0, true).orElseThrow().amount());
        assertEquals("water", GoalIntent.validated("go_to", "", "water", 0, false).orElseThrow().place());
        assertFalse(GoalIntent.validated("go_to", "", "nether", 0, false).isPresent());
        assertFalse(GoalIntent.validated("speak", "", "", 0, false).isPresent(), "speaking is the conversation model's own job");
        assertFalse(GoalIntent.validated("follow", "", "", 0, true).orElseThrow().deliverToOwner());
    }
}
