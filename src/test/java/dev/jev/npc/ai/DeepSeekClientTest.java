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

    static String planJson() {
        return """
            {"objective":"交付十二块圆石后回家","constraints":["受伤先治疗"],"completion":"交付十二块圆石并到家",
             "stages":[{"purpose":"采集十二块圆石交给主人","tool":"mine","material":"stone","amount":12,"deliver_to_owner":true},
                       {"purpose":"回家","tool":"go_to","place":"home"}]}
            """;
    }

    @Test void sendsJsonModeWithoutThinkingAndReadsAnOpenComposedGoal() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<JsonObject> payload = new AtomicReference<>();
        serve(200, completion("{\"reply\":\"好，我去采集十二块圆石给你，再回家。\",\"goal_change\":\"replace\",\"plan\":" + planJson() + "}"), auth, payload);
        var reply = client.chat("ds-test-key", "deepseek-flash", new Conversation().messages("谨慎", new JsonObject(), "拿十二块圆石再回家"), 2000).get(3, TimeUnit.SECONDS);
        assertEquals("Bearer ds-test-key", auth.get());
        assertEquals("json_object", payload.get().getAsJsonObject("response_format").get("type").getAsString());
        assertEquals("disabled", payload.get().getAsJsonObject("thinking").get("type").getAsString());
        assertTrue(payload.get().getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("json"));
        assertEquals(12, reply.plan().stages().getFirst().method().amount());
        assertEquals(2, reply.plan().stages().size());
        assertEquals(321, reply.promptTokens());
    }

    @Test void diagnosticTraceKeepsExactMessagesAndUnparsedLanguageOutput(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
        var sent=new AtomicReference<JsonObject>();
        String answer=completion("{\"reply\":\"你好 trace-ds-key\",\"goal_change\":\"keep\"}");
        serve(200, answer, null, sent);
        java.nio.file.Path page;
        try(var recorder=new dev.jev.npc.trace.TraceRecorder(folder, message -> fail(message))) {
            page=recorder.page();
            client.chat("trace-ds-key", "deepseek-flash", List.of(new DeepSeekClient.Message("system", "exact instructions"),
                new DeepSeekClient.Message("user", "<script>window.pwned=true</script>你好")), 2000, DialogueSession.Mode.UNDERSTAND_PLAYER,
                recorder.root("deepseek", new JsonObject(), new JsonObject())).get(3,TimeUnit.SECONDS);
        }
        String text=java.nio.file.Files.readString(page.resolveSibling("events.jsonl"));
        assertFalse(text.contains("trace-ds-key"));
        var rows=text.lines().map(line->JsonParser.parseString(line).getAsJsonObject()).toList();
        var request=rows.stream().filter(row->row.get("phase").getAsString().equals("request")).findFirst().orElseThrow();
        assertEquals(sent.get(),request.getAsJsonObject("data").getAsJsonObject("input"));
        var response=rows.stream().filter(row->row.get("phase").getAsString().equals("response")).findFirst().orElseThrow();
        assertEquals(JsonParser.parseString(answer.replace("trace-ds-key", "[REDACTED]")),response.getAsJsonObject("data").get("output"));
        assertFalse(java.nio.file.Files.readString(page).contains("<script>window.pwned"));
    }

    @Test void chatOnlyRepliesCarryNoGoal() {
        var reply = DeepSeekClient.parse(completion("{\"reply\":\"今天天气不错。\",\"goal_change\":\"keep\"}"), 5);
        assertFalse(reply.hasTask());
    }

    @Test void amendmentReferencesRoundTripAndRejectMalformedIndices() {
        String json = planJson().replace("\"amount\":12", "\"amount\":8,\"from_stage\":0");
        var reply = DeepSeekClient.parse(completion("{\"reply\":\"总共八块\",\"goal_change\":\"amend\",\"plan\":" + json + "}"), 5);
        assertEquals(0, reply.plan().stages().getFirst().fromStage());
        assertEquals(reply.plan(), GoalPlan.parse(reply.plan().json()));
        for (String index : List.of("-1", "8", "0.5", "\"0\"")) {
            String invalid = json.replace("\"from_stage\":0", "\"from_stage\":" + index);
            assertThrows(JevClient.JevFailure.class, () -> DeepSeekClient.parse(completion("{\"goal_change\":\"amend\",\"plan\":" + invalid + "}"), 5));
        }
    }

    @Test void inventedToolsOrParametersCannotBecomeExecutablePlans() {
        for (String invalid : List.of(planJson().replace("stone", "diamond"), planJson().replace("mine", "teleport"),
            planJson().replace(":12", ":-2"), planJson().replace(":12", ":12.5"), planJson().replace(":12", ":257"))) {
            assertThrows(JevClient.JevFailure.class, () -> DeepSeekClient.parse(completion("{\"reply\":\"好的\",\"goal_change\":\"replace\",\"plan\":" + invalid + "}"), 5));
        }
    }

    @Test void speechModeDiscardsPlansAndPermissionEvenWhenProviderViolatesProtocol() {
        var reply = DeepSeekClient.parse(completion("{\"reply\":\"天黑了\",\"goal_change\":\"replace\",\"answer\":\"yes\",\"plan\":" + planJson() + "}"), 5, DialogueSession.Mode.COMPOSE_SPEECH);
        assertNull(reply.plan());
        assertEquals("keep", reply.change());
        assertEquals("", reply.answer());
    }

    @Test void replyIsOneBoundedChatLineWithoutFormattingCodes() {
        String longText = "§c第一行\n第二行" + "很".repeat(400);
        JsonObject answer = new JsonObject();
        answer.addProperty("reply", longText);
        answer.addProperty("goal_change", "keep");
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
        for (int i = 0; i < 10; i++) conversation.record("问题" + i, new DeepSeekClient.Reply("回答" + i, null, "keep", "", 0, 0));
        var messages = conversation.messages("谨慎", new JsonObject(), "最新");
        assertEquals(1 + Conversation.MAX_MESSAGES + 1, messages.size());
        assertEquals("问题6", messages.get(1).content());
        assertEquals("最新", messages.getLast().content());
    }

    @Test void conversationHistoryPreservesTheAcceptedProposal() {
        var conversation = new Conversation();
        var plan = GoalPlan.parse(JsonParser.parseString(planJson()).getAsJsonObject());
        var reply = new DeepSeekClient.Reply("好的", plan, "replace", "", 0, 0);
        conversation.record("去吧", reply);
        var previous = JsonParser.parseString(conversation.messages("谨慎", new JsonObject(), "为什么？").get(2).content()).getAsJsonObject();
        assertEquals("replace", previous.get("goal_change").getAsString());
        assertEquals(plan.json(), previous.getAsJsonObject("plan"));
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
