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

    private JevClient localClient() { return new JevClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone")); }
}
