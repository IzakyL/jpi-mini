package io.github.izakyl.jpi.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.izakyl.jpi.FakeServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class OpenAiCompletionsTest {

    FakeServer server;
    ProviderRegistry registry;
    final StreamOptions options = new StreamOptions("test-key");
    final Context context = new Context("Be helpful", List.of(new UserMessage("Hello")));

    @BeforeEach
    void setup() throws Exception {
        server = new FakeServer();
        registry = ProviderRegistry.defaults(HttpClient.newHttpClient());
    }

    @AfterEach
    void cleanup() {
        server.close();
        assertEquals(List.of(), server.failures);
    }

    @Test
    void textOrderUsageSnapshotsAndWorkerCallbacks() throws Exception {
        server.fixture("text");
        var model = Model.builder("fake", server.baseUrl()).cost(new Model.Cost(1, 2, 0.5, 0)).build();
        var stream = registry.stream(model, context, options);
        var events = new CopyOnWriteArrayList<AssistantMessageEvent>();
        var threads = new CopyOnWriteArrayList<String>();
        stream.subscribe(
                event -> {
                    events.add(event);
                    threads.add(Thread.currentThread().getName());
                });
        var message = stream.result().get(5, TimeUnit.SECONDS);
        assertEquals("Hello 世界", message.text());
        assertEquals(StopReason.STOP, message.stopReason());
        assertEquals(List.of("start", "text_start", "text_delta", "text_delta", "text_end", "done"), types(events));
        assertEquals("", events.get(1).message().text());
        assertEquals("Hello", events.get(2).message().text());
        assertEquals(12, message.usage().input());
        assertEquals(8, message.usage().cacheRead());
        assertEquals(5, message.usage().output());
        assertEquals(2, message.usage().reasoning());
        assertEquals(25, message.usage().totalTokens());
        assertEquals(0.000026, message.usage().cost().total(), 1e-12);
        assertTrue(threads.stream().allMatch(name -> name.startsWith("jpi-mini-worker-")));
        assertEquals(List.of("Bearer test-key"), server.authorization);
        assertEquals("/v1", URI.create(server.baseUrl()).getPath());
    }

    @Test
    void fragmentedArgumentsAreParsedDuringStreaming() throws Exception {
        server.fixture("tool");
        var stream = registry.stream(server.model(), context, options);
        var events = new CopyOnWriteArrayList<AssistantMessageEvent>();
        stream.subscribe(events::add);
        var result = stream.result().get(5, TimeUnit.SECONDS);
        assertEquals(StopReason.TOOL_USE, result.stopReason());
        var call = result.toolCalls().get(0);
        assertEquals("call_weather", call.id());
        assertEquals("weather", call.name());
        assertEquals("Paris", call.arguments().get("city").getAsString());
        assertEquals(2, call.arguments().get("days").getAsInt());
        assertNull(call.argumentsError());
        var partials =
                events.stream()
                        .filter(e -> e.type().equals("toolcall_delta"))
                        .map(e -> e.message().toolCalls().get(0).arguments())
                        .toList();
        assertTrue(partials.stream().anyMatch(a -> a.has("city") && a.get("city").getAsString().equals("P")));
        assertTrue(partials.stream().anyMatch(a -> a.has("city") && a.get("city").getAsString().equals("Par")));
        assertTrue(events.get(1) instanceof AssistantMessageEvent.BlockStart);
        assertTrue(events.get(events.size() - 2) instanceof AssistantMessageEvent.BlockEnd);
    }

    @Test
    void parallelToolCallsRemainIndependent() throws Exception {
        server.fixture("parallel");
        var result = registry.stream(server.model(), context, options).result().get(5, TimeUnit.SECONDS);
        assertEquals(2, result.toolCalls().size());
        assertEquals(List.of("a", "b"), result.toolCalls().stream().map(ContentBlock.ToolCall::id).toList());
        assertEquals(
                List.of("Paris", "Tokyo"),
                result.toolCalls().stream().map(c -> c.arguments().get("city").getAsString()).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"reasoning", "reasoning-alias"})
    void reasoningAndAlias(String fixture) throws Exception {
        server.fixture(fixture);
        var stream = registry.stream(server.model(), context, options);
        var events = new CopyOnWriteArrayList<AssistantMessageEvent>();
        stream.subscribe(events::add);
        var result = stream.result().get(5, TimeUnit.SECONDS);
        assertInstanceOf(ContentBlock.Thinking.class, result.content().get(0));
        var thinking = (ContentBlock.Thinking) result.content().get(0);
        assertEquals(fixture.equals("reasoning") ? "Let me think." : "Consider.", thinking.thinking());
        assertTrue(types(events).containsAll(List.of("thinking_start", "thinking_delta", "thinking_end")));
        assertTrue(types(events).indexOf("thinking_start") < types(events).indexOf("text_start"));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 429, 500, 503})
    void httpErrorsResolveAsMessages(int status) throws Exception {
        server.status(status, "{\"error\":{\"message\":\"Bearer fixture-secret\"}}");
        var secretOptions = StreamOptions.builder().apiKey("fixture-secret").build();
        var stream = registry.stream(server.model(), context, secretOptions);
        var terminal = new CompletableFuture<AssistantMessageEvent>();
        stream.subscribe(terminal::complete);
        var result = stream.result().get(5, TimeUnit.SECONDS);
        assertEquals(StopReason.ERROR, result.stopReason());
        assertTrue(result.errorMessage().contains("HTTP " + status));
        assertFalse(result.errorMessage().contains("fixture-secret"));
        assertInstanceOf(AssistantMessageEvent.Error.class, terminal.get(5, TimeUnit.SECONDS));
    }

    @Test
    void abortClosesStreamingBodyAndKeepsPartialText() throws Exception {
        var release = new CountDownLatch(1);
        server.enqueue(
                exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody()
                            .write(
                                    "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
                                            .getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    release.await(10, TimeUnit.SECONDS);
                });
        var token = new CancellationToken();
        var stream = registry.stream(server.model(), context, options.withCancellation(token));
        var seen = new CountDownLatch(1);
        stream.subscribe(
                e -> {
                    if (e.type().equals("text_delta")) {
                        seen.countDown();
                    }
                });
        assertTrue(seen.await(5, TimeUnit.SECONDS));
        try {
            token.cancel();
            var result = stream.result().get(3, TimeUnit.SECONDS);
            assertEquals(StopReason.ABORTED, result.stopReason());
            assertEquals("partial", result.text());
        } finally {
            release.countDown();
        }
    }

    @Test
    void abortBeforeHeadersCancelsRequest() throws Exception {
        var arrived = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.enqueue(
                exchange -> {
                    arrived.countDown();
                    release.await(10, TimeUnit.SECONDS);
                });
        var token = new CancellationToken();
        var stream = registry.stream(server.model(), context, options.withCancellation(token));
        assertTrue(arrived.await(5, TimeUnit.SECONDS));
        try {
            token.cancel();
            assertEquals(StopReason.ABORTED, stream.result().get(3, TimeUnit.SECONDS).stopReason());
        } finally {
            release.countDown();
        }
    }

    @Test
    void preCancelledTokenSkipsHttp() throws Exception {
        var token = new CancellationToken();
        token.cancel();
        var result =
                registry.stream(server.model(), context, options.withCancellation(token))
                        .result()
                        .get(5, TimeUnit.SECONDS);
        assertEquals(StopReason.ABORTED, result.stopReason());
        assertTrue(server.requests.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "data: {\"error\":{\"message\":\"broken\"}}\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"truncated\"}}]}\n\ndata: [DONE]\n\n",
                "data: invalid JSON\n\n",
                "data: {\"choices\":[{\"finish_reason\":\"content_filter\"}]}\n\n"
            })
    void sseFailuresResolveAsError(String fixture) throws Exception {
        server.status(200, fixture);
        assertEquals(
                StopReason.ERROR,
                registry.stream(server.model(), context, options).result().get(5, TimeUnit.SECONDS).stopReason());
    }

    @Test
    void malformedFinalArgumentsDoNotUseRepairedPreview() throws Exception {
        server.fixture("malformed-tool");
        var result = registry.stream(server.model(), context, options).result().get(5, TimeUnit.SECONDS);
        assertEquals(StopReason.TOOL_USE, result.stopReason());
        assertNotNull(result.toolCalls().get(0).argumentsError());
        assertEquals(new JsonObject(), result.toolCalls().get(0).arguments());
    }

    @Test
    void sseCommentsMultilineDataAndRejectsUnterminatedFrame() throws Exception {
        server.status(
                200,
                ": keepalive\r\n"
                        + "event: message\r\n"
                        + "data: {\"choices\":\r\n"
                        + "data: [{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\r\n\r\n"
                        + "data:[DONE]");
        var result = registry.stream(server.model(), context, options).result().get(5, TimeUnit.SECONDS);
        assertEquals("ok", result.text());
        assertEquals(StopReason.ERROR, result.stopReason());
        assertEquals("Incomplete SSE frame", result.errorMessage());
    }

    @Test
    void lateListenersReplayAndListenerFailureCannotBreakStream() throws Exception {
        server.fixture("text");
        var stream = registry.stream(server.model(), context, options);
        stream.subscribe(
                event -> {
                    throw new IllegalStateException("observer failure");
                });
        stream.result().get(5, TimeUnit.SECONDS);
        var replay = new CopyOnWriteArrayList<AssistantMessageEvent>();
        var done = new CountDownLatch(1);
        stream.subscribe(
                event -> {
                    replay.add(event);
                    if (event instanceof AssistantMessageEvent.Done) {
                        done.countDown();
                    }
                });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("start", "text_start", "text_delta", "text_delta", "text_end", "done"), types(replay));
    }

    @Test
    void registryAndResolverFailuresStayInsideContract() throws Exception {
        var missing = new ProviderRegistry();
        assertEquals(
                StopReason.ERROR,
                missing.stream(server.model(), context, options).result().get(5, TimeUnit.SECONDS).stopReason());
        missing.register(
                "openai-completions",
                (m, c, o) -> {
                    throw new IllegalStateException("provider setup");
                });
        assertTrue(
                missing.stream(server.model(), context, options)
                        .result()
                        .get(5, TimeUnit.SECONDS)
                        .errorMessage()
                        .contains("provider setup"));
        missing.unregister("openai-completions");
        var badResolver =
                StreamOptions.builder()
                        .apiKeyResolver(
                                m -> {
                                    throw new IOExceptionForTest();
                                })
                        .build();
        assertEquals(
                StopReason.ERROR,
                registry.stream(server.model(), context, badResolver).result().get(5, TimeUnit.SECONDS).stopReason());
    }

    @Test
    void requestCompatibilityAndReasoningReplay() throws Exception {
        server.fixture("final");
        var compat =
                OpenAiCompat.builder()
                        .supportsStore(false)
                        .supportsDeveloperRole(false)
                        .supportsUsageInStreaming(false)
                        .supportsReasoningEffort(false)
                        .maxTokensField("max_tokens")
                        .requiresToolResultName(true)
                        .requiresReasoningContent(true)
                        .requiresAssistantAfterToolResult(true)
                        .build();
        var model =
                Model.builder("fake", server.baseUrl())
                        .reasoning(true)
                        .compat(compat)
                        .headers(Map.of("Authorization", "Bearer model"))
                        .build();
        var assistant =
                new AssistantMessage(
                        List.of(
                                new ContentBlock.Thinking("Reason", "reasoning_content"),
                                new ContentBlock.ToolCall("c", "weather", new JsonObject())),
                        model.api(),
                        model.provider(),
                        model.id(),
                        Usage.ZERO,
                        StopReason.TOOL_USE,
                        null,
                        0);
        var history =
                new Context(
                        "Instructions",
                        List.of(
                                new UserMessage("hi"),
                                assistant,
                                new ToolResultMessage(
                                        "c", "weather", List.of(new ContentBlock.Text("sun")), null, false, 0),
                                new UserMessage("more")));
        var requestOptions =
                StreamOptions.builder()
                        .apiKeyResolver(m -> "resolved")
                        .reasoningEffort("high")
                        .maxTokens(123)
                        .headers(Map.of("authorization", "Bearer call"))
                        .build();
        assertEquals(
                StopReason.STOP,
                registry.stream(model, history, requestOptions).result().get(5, TimeUnit.SECONDS).stopReason());
        var body = server.requests.get(0);
        assertEquals(123, body.get("max_tokens").getAsInt());
        for (var key : List.of("store", "stream_options", "reasoning_effort", "max_completion_tokens")) {
            assertFalse(body.has(key), key);
        }
        var messages = body.getAsJsonArray("messages");
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("Reason", messages.get(2).getAsJsonObject().get("reasoning_content").getAsString());
        assertEquals("weather", messages.get(3).getAsJsonObject().get("name").getAsString());
        assertEquals("assistant", messages.get(4).getAsJsonObject().get("role").getAsString());
        assertTrue(body.has("tools"));
        assertEquals("Bearer call", server.authorization.get(0));
    }

    @Test
    void defaultDeveloperRoleAndOptionalFinishReason() throws Exception {
        server.status(200, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n");
        var model =
                Model.builder("fake", server.baseUrl())
                        .reasoning(true)
                        .compat(OpenAiCompat.builder().supportsFinishReason(false).build())
                        .build();
        var opts = StreamOptions.builder().apiKeyResolver(m -> "resolved").reasoningEffort("low").build();
        var result = registry.stream(model, context, opts).result().get(5, TimeUnit.SECONDS);
        assertEquals(StopReason.STOP, result.stopReason());
        var body = server.requests.get(0);
        assertEquals("developer", body.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
        assertFalse(body.get("store").getAsBoolean());
        assertEquals("low", body.get("reasoning_effort").getAsString());
        assertEquals("Bearer resolved", server.authorization.get(0));
    }

    @Test
    void imagesAndThinkingAsTextAreConvertedForReplay() throws Exception {
        server.fixture("final");
        var compat = OpenAiCompat.builder().requiresThinkingAsText(true).build();
        var model = Model.builder("fake", server.baseUrl()).compat(compat).input(List.of("text", "image")).build();
        var assistant =
                new AssistantMessage(
                        List.of(new ContentBlock.Thinking("First reason."), new ContentBlock.Text("Then answer.")),
                        model.api(),
                        model.provider(),
                        model.id(),
                        Usage.ZERO,
                        StopReason.STOP,
                        null,
                        0);
        var image = new ContentBlock.Image("AA==", "image/png");
        var imageContext =
                new Context(
                        "",
                        List.of(
                                new UserMessage(List.of(new ContentBlock.Text("Look"), image), 0),
                                assistant,
                                new ToolResultMessage(
                                        "id",
                                        "picture",
                                        List.of(new ContentBlock.Text("image result"), image),
                                        null,
                                        false,
                                        0)));
        registry.stream(model, imageContext, options).result().get(5, TimeUnit.SECONDS);
        var messages = server.requests.get(0).getAsJsonArray("messages");
        assertEquals(
                "data:image/png;base64,AA==",
                messages.get(0)
                        .getAsJsonObject()
                        .getAsJsonArray("content")
                        .get(1)
                        .getAsJsonObject()
                        .getAsJsonObject("image_url")
                        .get("url")
                        .getAsString());
        var replay = messages.get(1).getAsJsonObject();
        assertEquals("First reason.\n\nThen answer.", replay.get("content").getAsString());
        assertFalse(replay.has("reasoning_content"));
        assertEquals("image result", messages.get(2).getAsJsonObject().get("content").getAsString());
        assertEquals("user", messages.get(3).getAsJsonObject().get("role").getAsString());
        assertEquals(2, messages.get(3).getAsJsonObject().getAsJsonArray("content").size());
    }

    @Test
    void imagesFromParallelToolResultsFollowEveryToolMessage() throws Exception {
        server.fixture("final");
        var model = Model.builder("fake", server.baseUrl()).input(List.of("text", "image")).build();
        var assistant =
                new AssistantMessage(
                        List.of(
                                new ContentBlock.ToolCall("first", "run", new com.google.gson.JsonObject()),
                                new ContentBlock.ToolCall("second", "run", new com.google.gson.JsonObject())),
                        model.api(),
                        model.provider(),
                        model.id(),
                        Usage.ZERO,
                        StopReason.TOOL_USE,
                        null,
                        0);
        var image = new ContentBlock.Image("AA==", "image/png");
        var context =
                new Context(
                        "",
                        List.of(
                                new UserMessage(List.of(new ContentBlock.Text("Look")), 0),
                                assistant,
                                new ToolResultMessage(
                                        "first", "run", List.of(new ContentBlock.Text("shot"), image), null, false, 0),
                                new ToolResultMessage(
                                        "second", "run", List.of(new ContentBlock.Text("read")), null, false, 0)));
        registry.stream(model, context, options).result().get(5, TimeUnit.SECONDS);
        var messages = server.requests.get(0).getAsJsonArray("messages");
        assertEquals("tool", messages.get(2).getAsJsonObject().get("role").getAsString());
        assertEquals("first", messages.get(2).getAsJsonObject().get("tool_call_id").getAsString());
        assertEquals("tool", messages.get(3).getAsJsonObject().get("role").getAsString());
        assertEquals("second", messages.get(3).getAsJsonObject().get("tool_call_id").getAsString());
        assertEquals("user", messages.get(4).getAsJsonObject().get("role").getAsString());
        assertEquals(2, messages.get(4).getAsJsonObject().getAsJsonArray("content").size());
        assertEquals(5, messages.size());
    }

    private static List<String> types(List<AssistantMessageEvent> events) {
        return events.stream().map(AssistantMessageEvent::type).toList();
    }

    private static final class IOExceptionForTest extends Exception {
    }
}
