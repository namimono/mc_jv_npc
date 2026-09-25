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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class JevClientTest {
    private static final List<Candidate> OPTIONS = List.of(new Candidate("continue_current", "Keep working"), new Candidate("follow", "Follow owner"));
    private HttpServer server;
    private JevClient client;
    private static final String VALID = """
        {"model":"jev-1.13.0","answers":{"next_action":{"type":"choice","choice":"follow","confidence":0.82,
        "probabilities":{"follow":0.91,"continue_current":0.09}}},"usage":{"input_tokens":534,"output_tokens":24}}
        """;

    @AfterEach void cleanup() { if (client != null) client.close(); if (server != null) server.stop(0); }

    @Test void sendsOfficialContractAndReadsDecision() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonObject> payload = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            payload.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            byte[] response = VALID.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        client = localClient();
        JsonObject state = new JsonObject();
        state.addProperty("owner_request", "跟着我");
        Decision result = client.decide("test-key-only", "jev-1.13.0", state, OPTIONS, 1000).get(3, TimeUnit.SECONDS);
        assertEquals("Bearer test-key-only", authorization.get());
        assertEquals("跟着我", payload.get().getAsJsonObject("state").get("owner_request").getAsString());
        var question = payload.get().getAsJsonObject("questions").getAsJsonObject("next_action");
        assertEquals("choice", question.get("type").getAsString());
        assertEquals(2, question.getAsJsonObject("criteria").size());
        assertEquals("follow", result.candidateId());
        assertEquals(534, result.inputTokens());
    }

    @Test void rejectsUnknownActionEvenIfProviderReturnsIt() {
        var error = assertThrows(JevClient.JevFailure.class, () -> JevClient.parse(VALID.replace("\"choice\":\"follow\"", "\"choice\":\"op_owner\""), OPTIONS, 10));
        assertEquals("INVALID_RESPONSE", error.getMessage());
    }

    @Test void rejectsMissingAndOutOfRangeConfidence() {
        assertThrows(JevClient.JevFailure.class, () -> JevClient.parse(VALID.replace("0.82", "1.8"), OPTIONS, 10));
        assertThrows(JevClient.JevFailure.class, () -> JevClient.parse("{}", OPTIONS, 10));
    }

    @Test void doesNotExposeErrorBodyOrRetryRateLimit() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            requests.incrementAndGet();
            byte[] response = "secret-input-that-must-not-be-logged".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        client = localClient();
        var error = assertThrows(java.util.concurrent.CompletionException.class,
            () -> client.decide("test-key-only", "jev-1.13.0", new JsonObject(), OPTIONS, 1000).join());
        assertEquals("HTTP_429", JevClient.errorCode(error));
        assertEquals(1, requests.get());
        assertFalse(error.toString().contains("secret-input"));
    }

    @Test void timeoutCompletesWithoutWaitingForServerWork() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            try { Thread.sleep(800); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        client = localClient();
        var future = client.decide("test-key-only", "jev-1.13.0", new JsonObject(), OPTIONS, 100);
        var error = assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(600, TimeUnit.MILLISECONDS));
        assertEquals("NETWORK_OR_TIMEOUT", JevClient.errorCode(error.getCause()));
    }

    @Test void missingKeyNeverMakesRequest() {
        client = new JevClient();
        var error = assertThrows(java.util.concurrent.CompletionException.class,
            () -> client.decide("", "jev-1.13.0", new JsonObject(), OPTIONS, 1000).join());
        assertEquals("MISSING_KEY", JevClient.errorCode(error));
    }

    @Test void candidateIdsMustBeUnique() {
        assertThrows(IllegalArgumentException.class, () -> JevClient.payload("jev-1.13.0", new JsonObject(),
            List.of(new Candidate("same", "first"), new Candidate("same", "second"))));
    }

    @Test void malformedKeyFailsWithoutThrowingOnGameThread() {
        client = new JevClient();
        var future = client.decide("accidental\nnewline", "jev-1.13.0", new JsonObject(), OPTIONS, 1000);
        var error = assertThrows(java.util.concurrent.CompletionException.class, future::join);
        assertEquals("INVALID_KEY_FORMAT", JevClient.errorCode(error));
    }

    @Test void interpretationIgnoresUnusedLowConfidenceBranches() {
        String body = """
            {"model":"jev-1.13.0","answers":{
              "verb":{"type":"choice","choice":"go_to","confidence":0.9},
              "place":{"type":"choice","choice":"water","confidence":0.8},
              "amount":{"type":"choice","choice":"one","confidence":0.01},
              "material":{"type":"choice","choice":"ground","confidence":0.01}
            }}
            """;
        Decision decision = JevClient.parseIntent(body, 30);
        assertEquals(0.8, decision.confidence());
        assertEquals("water", decision.intent().place());
        assertThrows(JevClient.JevFailure.class, () -> JevClient.parseIntent(body.replace("water", "invented_coordinates"), 30));
    }

    @Test void interpretationUsesNoulProbabilityAndSelectedAmount() {
        String body = """
            {"model":"jev-1.13.0","answers":{
              "verb":{"type":"choice","choice":"harvest","confidence":0.9},
              "amount":{"type":"choice","choice":"a_few","confidence":0.8},
              "amount_explicit":{"type":"noul","noul":0.99},
              "deliver_to_owner":{"type":"noul","noul":0.5}
            }}
            """;
        Decision decision = JevClient.parseIntent(body, 20);
        assertEquals(4, decision.intent().amount());
        assertTrue(decision.intent().deliverToOwner());
        assertEquals(0.8, decision.confidence());
        assertFalse(JevClient.parseIntent(body.replace("0.5", "0.2"), 20).intent().deliverToOwner());
        assertThrows(JevClient.JevFailure.class, () -> JevClient.parseIntent(body.replace("0.5", "2.5"), 20));
    }

    @Test void initialGoalUsesTypedQuestionsThenToolResultsReachNextRequest() {
        AgentTask task = new AgentTask("去水里");
        JsonObject state = new JsonObject();
        state.add("task", task.state());
        var initial = JevClient.payload("jev-1.13.0", state, List.of());
        assertTrue(initial.getAsJsonObject("questions").has("verb"));
        assertFalse(initial.getAsJsonObject("questions").has("next_action"));
        task.intent = new GoalIntent("go_to", "log", "water", 1, false);
        task.feedback("observe_nearby", true, 1, "water_1 at observed coordinates");
        state.add("task", task.state());
        var next = JevClient.payload("jev-1.13.0", state, List.of(new Candidate("water_1", "Go to observed water")));
        assertTrue(next.getAsJsonObject("questions").has("next_action"));
        assertEquals(1, next.getAsJsonObject("state").getAsJsonObject("task").getAsJsonArray("tool_results").size());
    }

    @Test void absentQuantityCannotInvalidateClearlyUnderstoodDigRequest() {
        // Reproduced live for 挖地面: verb/material=1.0, amount=unsupported with confidence 0.23.
        String body = """
            {"model":"jev-1.13.0","answers":{
              "verb":{"type":"choice","choice":"mine","confidence":1.0},
              "material":{"type":"choice","choice":"ground","confidence":1.0},
              "amount":{"type":"choice","choice":"unsupported","confidence":0.23},
              "amount_explicit":{"type":"noul","noul":0.01},
              "deliver_to_owner":{"type":"noul","noul":0.8}
            }}
            """;
        var decision = JevClient.parseIntent(body, 10);
        assertEquals("mine", decision.intent().verb());
        assertEquals(1, decision.intent().amount());
        assertEquals(1.0, decision.confidence());
        assertEquals("unsupported", JevClient.parseIntent(body.replace("0.01", "0.99"), 10).intent().verb());
    }

    @Test void replyInterpretationFallsBackToOtherWhenUnsureOrUnknown() {
        var options = Communicator.yesNo("The owner agrees", "The owner refuses");
        var payload = JevClient.replyPayload("jev-1.13.0", "要冒险吗？", options, "你看着办");
        var question = payload.getAsJsonObject("questions").getAsJsonObject("answer");
        assertEquals(3, question.getAsJsonObject("criteria").size(), "yes, no and other");
        assertEquals("你看着办", payload.getAsJsonObject("state").get("owner_reply").getAsString());
        String body = """
            {"model":"jev-1.13.0","answers":{"answer":{"type":"choice","choice":"yes","confidence":0.8}}}
            """;
        assertEquals("yes", JevClient.parseReply(body, options));
        assertEquals("other", JevClient.parseReply(body.replace("0.8", "0.3"), options), "unsure answers are not consent");
        assertEquals("other", JevClient.parseReply(body.replace("\"yes\"", "\"other\""), options));
        assertThrows(JevClient.JevFailure.class, () -> JevClient.parseReply("{}", options));
    }

    private JevClient localClient() { return new JevClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone")); }
}
