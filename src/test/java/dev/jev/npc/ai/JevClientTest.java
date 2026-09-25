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
import java.util.concurrent.CompletionException;
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
        result = client.choose("test-key-only", "jev-1.13.0", state, OPTIONS, "Review this specific dialogue proposal", 1000).get(3, TimeUnit.SECONDS);
        assertEquals("follow", result.candidateId());
        assertEquals("Review this specific dialogue proposal", payload.get().getAsJsonObject("questions").getAsJsonObject("next_action").get("instructions").getAsString());
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

    @Test void goalStateNeverTriggersALegacyRestrictedIntentClassifier() {
        var state = new JsonObject();
        var task = new AgentTask("挖十二块圆石然后回家");
        state.add("task", task.state());
        var payload = JevClient.payload("jev-1.13.0", state, OPTIONS);
        assertEquals(1, payload.getAsJsonObject("questions").size());
        assertTrue(payload.getAsJsonObject("questions").has("next_action"));
        assertFalse(payload.getAsJsonObject("questions").has("amount"));
        task.feedback("mine", true, 12, "actual drops collected");
        state.add("task", task.state());
        assertEquals(12, JevClient.payload("jev-1.13.0", state, OPTIONS).getAsJsonObject("state")
            .getAsJsonObject("task").getAsJsonArray("tool_results").get(0).getAsJsonObject().get("progress").getAsInt());
    }

    @Test void diagnosticTracePreservesActualQuestionAllProbabilitiesAndErrorBody(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
        var body = new AtomicReference<>(VALID);
        var status = new AtomicInteger(200);
        var captured = new AtomicReference<JsonObject>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            captured.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start(); client=localClient();
        java.nio.file.Path page;
        try (var recorder = new dev.jev.npc.trace.TraceRecorder(folder, message -> fail(message))) {
            page=recorder.page();
            var trace=recorder.root("jev", new JsonObject(), new JsonObject());
            client.choose("trace-secret", "jev-1.13.0", new JsonObject(), OPTIONS, "Route the exact current message", 1000, trace).get(3, TimeUnit.SECONDS);
            status.set(429); body.set("provider rejected trace-secret; Bearer hidden-token");
            assertThrows(CompletionException.class, () -> client.decide("trace-secret", "jev-1.13.0", new JsonObject(), OPTIONS, 1000,
                trace.child("jev", new JsonObject())).join());
        }
        String text=java.nio.file.Files.readString(page.resolveSibling("events.jsonl"));
        assertFalse(text.contains("trace-secret")); assertFalse(text.contains("hidden-token"));
        var rows=text.lines().map(line->JsonParser.parseString(line).getAsJsonObject()).toList();
        var request=rows.stream().filter(row->row.get("phase").getAsString().equals("request")).findFirst().orElseThrow();
        assertEquals("Route the exact current message", request.getAsJsonObject("data").getAsJsonObject("input")
            .getAsJsonObject("questions").getAsJsonObject("next_action").get("instructions").getAsString());
        var response=rows.stream().filter(row->row.get("phase").getAsString().equals("response")).findFirst().orElseThrow();
        var answer=response.getAsJsonObject("data").getAsJsonObject("output").getAsJsonObject("answers").getAsJsonObject("next_action");
        assertEquals(0.91,answer.getAsJsonObject("probabilities").get("follow").getAsDouble());
        assertEquals(0.09,answer.getAsJsonObject("probabilities").get("continue_current").getAsDouble());
        assertEquals(0.82,answer.get("confidence").getAsDouble());
        assertTrue(text.contains("provider rejected [REDACTED]"));
        assertTrue(text.contains("429"));
    }

    private JevClient localClient() { return new JevClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone")); }
}
