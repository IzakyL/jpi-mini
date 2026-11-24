package io.github.izakyl.jpi.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import io.github.izakyl.jpi.FakeServer;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class GoogleGenerativeAiTest {

    @Test
    void streamsAndReplaysSignaturesAndImages() throws Exception {
        try (var server = new FakeServer("/v1/models/fixture:streamGenerateContent")) {
            server.status(200, "data: {\"candidates\":[{\"content\":{\"parts\":["
                    + "{\"text\":\"think\",\"thought\":true,\"thoughtSignature\":\"YWJj\"},"
                    + "{\"text\":\"hello\",\"thoughtSignature\":\"ZGVm\"},"
                    + "{\"functionCall\":{\"id\":\"id1\",\"name\":\"lookup\",\"args\":\"{\\\"city\\\":\\\"\"},"
                    + "\"thoughtSignature\":\"Z2hp\"}]} }]}\n\n"
                    + "data: {\"candidates\":[{\"content\":{\"parts\":["
                    + "{\"functionCall\":{\"id\":\"id1\",\"args\":\"Paris\\\"}\"}}]},"
                    + "\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":10,"
                    + "\"cachedContentTokenCount\":3,\"candidatesTokenCount\":4,\"thoughtsTokenCount\":2,"
                    + "\"totalTokenCount\":16}}\n\n");
            server.status(200, "data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n");
            var model = Model.builder("fixture", server.baseUrl()).api(GoogleGenerativeAi.API)
                    .provider("google").input(List.of("text", "image")).build();
            var image = new UserMessage(List.of(new ContentBlock.Image("YWJj", "image/png")), 1);
            var registry = ProviderRegistry.defaults();
            var first = registry.stream(model, new Context("system", List.of(image)), new StreamOptions("key"))
                    .result().get(5, TimeUnit.SECONDS);
            assertEquals(StopReason.TOOL_USE, first.stopReason(), first.errorMessage());
            assertEquals("hello", first.text());
            assertEquals("Paris", first.toolCalls().get(0).arguments().get("city").getAsString());
            assertEquals(7, first.usage().input());
            assertEquals(3, first.usage().cacheRead());
            assertEquals(6, first.usage().output());
            var result = new ToolResultMessage("id1", "lookup", List.of(new ContentBlock.Image("YWJj", "image/png")),
                    null, false, 1);
            var second = registry.stream(model, new Context(null, List.of(image, first, result)),
                    new StreamOptions("key")).result().get(5, TimeUnit.SECONDS);
            assertEquals(StopReason.STOP, second.stopReason(), second.errorMessage());
            var request = server.requests.get(1);
            var contents = request.getAsJsonArray("contents");
            assertEquals("YWJj", contents.get(0).getAsJsonObject().getAsJsonArray("parts").get(0)
                    .getAsJsonObject().getAsJsonObject("inlineData").get("data").getAsString());
            var parts = contents.get(1).getAsJsonObject().getAsJsonArray("parts");
            assertEquals("YWJj", parts.get(0).getAsJsonObject().get("thoughtSignature").getAsString());
            assertEquals("ZGVm", parts.get(1).getAsJsonObject().get("thoughtSignature").getAsString());
            assertEquals("Z2hp", parts.get(2).getAsJsonObject().get("thoughtSignature").getAsString());
            assertTrue(contents.get(2).getAsJsonObject().getAsJsonArray("parts").get(0).getAsJsonObject()
                    .getAsJsonObject("functionResponse").has("parts"));
            assertEquals("key", server.headers.get(0).entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase("x-goog-api-key")).findFirst().orElseThrow()
                    .getValue().get(0));
        }
    }

    @Test
    void errorsAndCancellation() throws Exception {
        try (var server = new FakeServer("/v1/models/fixture:streamGenerateContent")) {
            server.status(401, "unauthorized");
            server.status(200, "data: {\"error\":{\"message\":\"bad\"}}\n\n");
            var model = Model.builder("fixture", server.baseUrl()).api(GoogleGenerativeAi.API).build();
            var registry = ProviderRegistry.defaults();
            var context = new Context(null, List.of());
            var first = registry.stream(model, context, new StreamOptions("key")).result().get(5, TimeUnit.SECONDS);
            assertEquals(StopReason.ERROR, first.stopReason());
            assertTrue(first.errorMessage().contains("401"));
            var second = registry.stream(model, context, new StreamOptions("key")).result().get(5, TimeUnit.SECONDS);
            assertEquals(StopReason.ERROR, second.stopReason());
            assertTrue(second.errorMessage().contains("bad"));
            var token = new CancellationToken();
            token.cancel();
            var aborted = registry.stream(model, context, new StreamOptions("key").withCancellation(token))
                    .result().get(5, TimeUnit.SECONDS);
            assertEquals(StopReason.ABORTED, aborted.stopReason());
        }
    }

    @Test
    void sendsToolSchemaAndCancelsAnActiveStream() throws Exception {
        try (var server = new FakeServer("/v1/models/fixture:streamGenerateContent")) {
            var started = new CountDownLatch(1);
            server.enqueue(exchange -> {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("data: {\"candidates\":[]}\n\n".getBytes());
                exchange.getResponseBody().flush();
                started.countDown();
                Thread.sleep(5000);
            });
            var model = Model.builder("fixture", server.baseUrl()).api(GoogleGenerativeAi.API).build();
            var schema = JsonParser.parseString("{\"type\":\"object\",\"properties\":{"
                    + "\"city\":{\"enum\":[\"Paris\"]}}}").getAsJsonObject();
            var context = new Context("system", List.of(new UserMessage("hello")),
                    List.of(new Tool("lookup", "Look up a city", schema)));
            var token = new CancellationToken();
            var stream = ProviderRegistry.defaults().stream(model, context,
                    new StreamOptions("key").withCancellation(token));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            token.cancel();
            assertEquals(StopReason.ABORTED, stream.result().get(5, TimeUnit.SECONDS).stopReason());
            var request = server.requests.get(0);
            assertTrue(request.has("systemInstruction"));
            var parameters = request.getAsJsonArray("tools").get(0).getAsJsonObject()
                    .getAsJsonArray("functionDeclarations").get(0).getAsJsonObject()
                    .getAsJsonObject("parametersJsonSchema");
            assertEquals("Paris", parameters.getAsJsonObject("properties").getAsJsonObject("city")
                    .getAsJsonArray("enum").get(0).getAsString());
        }
    }

    @Test
    void batchesParallelToolResponsesBeforeGeminiTwoImageFallback() {
        var model = Model.builder("gemini-2.5-flash", "https://example.com").input(List.of("text", "image")).build();
        var image = ToolResultMessage.builder("first", "picture")
                .content(List.of(new ContentBlock.Image("YWJj", "image/png"))).build();
        var lookup = ToolResultMessage.builder("second", "lookup")
                .content(List.of(new ContentBlock.Text("found"))).build();
        var provider = new GoogleGenerativeAi(HttpClient.newHttpClient());
        var contents = provider.requestBody(model, new Context(null, List.of(image, lookup)), StreamOptions.defaults())
                .getAsJsonArray("contents");
        assertEquals(2, contents.size());
        var responses = contents.get(0).getAsJsonObject().getAsJsonArray("parts");
        assertEquals(2, responses.size());
        assertTrue(responses.get(0).getAsJsonObject().has("functionResponse"));
        assertTrue(responses.get(1).getAsJsonObject().has("functionResponse"));
        var fallback = contents.get(1).getAsJsonObject().getAsJsonArray("parts");
        assertTrue(fallback.get(1).getAsJsonObject().has("inlineData"));
    }

    @Test
    void generatedModelsAreAvailable() {
        var model = Models.of("google").get(0);
        assertEquals(GoogleGenerativeAi.API, model.api());
        assertTrue(Models.get("google", model.id()).isPresent());
        assertTrue(model.contextWindow() > 0);
    }
}
