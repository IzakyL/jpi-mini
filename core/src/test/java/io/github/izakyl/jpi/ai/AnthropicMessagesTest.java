package io.github.izakyl.jpi.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.izakyl.jpi.FakeServer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AnthropicMessagesTest {

    private static String event(String json) {
        return "data: " + json + "\n\n";
    }

    @Test
    void streamsAndReplaysToolThinkingAndImages() throws Exception {
        try (var server = new FakeServer("/v1/messages")) {
            String response = event("{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":3,"
                    + "\"cache_read_input_tokens\":4,\"cache_creation_input_tokens\":5}}}")
                    + event("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{"
                    + "\"type\":\"thinking\",\"thinking\":\"\"}}")
                    + event("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{"
                    + "\"type\":\"thinking_delta\",\"thinking\":\"think\"}}")
                    + event("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{"
                    + "\"type\":\"signature_delta\",\"signature\":\"signed\"}}")
                    + event("{\"type\":\"content_block_stop\",\"index\":0}")
                    + event("{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{"
                    + "\"type\":\"tool_use\",\"id\":\"call\",\"name\":\"lookup\",\"input\":{}}}")
                    + event("{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{"
                    + "\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\"}}")
                    + event("{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{"
                    + "\"type\":\"input_json_delta\",\"partial_json\":\"\\\"Paris\\\"}\"}}")
                    + event("{\"type\":\"content_block_stop\",\"index\":1}")
                    + event("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},"
                    + "\"usage\":{\"output_tokens\":6}}") + event("{\"type\":\"message_stop\"}");
            server.enqueue(exchange -> FakeServer.send(exchange, 200, response));
            server.enqueue(exchange -> FakeServer.send(exchange, 200,
                    event("{\"type\":\"message_start\",\"message\":{}}")
                    + event("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{"
                    + "\"type\":\"text\",\"text\":\"\"}}")
                    + event("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{"
                    + "\"type\":\"text_delta\",\"text\":\"hello\"}}")
                    + event("{\"type\":\"content_block_stop\",\"index\":0}")
                    + event("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}")
                    + event("{\"type\":\"message_stop\"}")));
            var model = Model.builder("fixture", server.baseUrl()).api(AnthropicMessages.API)
                    .provider("anthropic").input(List.of("text", "image")).build();
            var image = new ContentBlock.Image("aGVsbG8=", "image/png");
            var user = new UserMessage(List.of(image), 1);
            var tool = new Tool("lookup", "Find", new JsonObject());
            var registry = ProviderRegistry.defaults();
            var options = new StreamOptions("secret");
            var first = registry.stream(model, new Context("system", List.of(user), List.of(tool)), options)
                    .result().get(5, TimeUnit.SECONDS);
            assertEquals(StopReason.TOOL_USE, first.stopReason());
            assertEquals("Paris", first.toolCalls().get(0).arguments().get("city").getAsString());
            assertEquals(18, first.usage().totalTokens());
            var result = new ToolResultMessage("call", "lookup", List.of(image), null, false, 2);
            var second = registry.stream(model, new Context(null, List.of(user, first, result)), options)
                    .result().get(5, TimeUnit.SECONDS);
            assertEquals("hello", second.text());
            var request = server.requests.get(1).getAsJsonArray("messages");
            assertEquals("signed", request.get(1).getAsJsonObject().getAsJsonArray("content").get(0)
                    .getAsJsonObject().get("signature").getAsString());
            assertEquals("image", request.get(2).getAsJsonObject().getAsJsonArray("content").get(0)
                    .getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().get("type").getAsString());
            assertEquals("system", server.requests.get(0).get("system").getAsString());
            assertTrue(server.headers.get(0).entrySet().stream().anyMatch(e -> e.getKey().equalsIgnoreCase("x-api-key")
                    && e.getValue().contains("secret")));
            assertTrue(server.failures.isEmpty());
        }
    }

    @Test
    void loadsAnthropicModels() {
        var models = Models.of("anthropic");
        assertTrue(!models.isEmpty());
        var model = Models.get("anthropic", models.get(0).id()).orElseThrow();
        assertEquals(AnthropicMessages.API, model.api());
        assertEquals("https://api.anthropic.com", model.baseUrl());
        assertTrue(model.maxTokens() > 0 && model.contextWindow() > 0);
    }

    @Test
    void reportsErrorsAndCancellation() throws Exception {
        try (var server = new FakeServer("/v1/messages")) {
            server.status(401, "unauthorized");
            server.enqueue(exchange -> FakeServer.send(exchange, 200,
                    event("{\"type\":\"error\",\"error\":{\"message\":\"overloaded\"}}")));
            var model = Model.builder("fixture", server.baseUrl()).api(AnthropicMessages.API).build();
            var provider = ProviderRegistry.defaults();
            var context = new Context(null, List.of(new UserMessage("hi")));
            var options = new StreamOptions("secret");
            assertEquals(StopReason.ERROR, provider.stream(model, context, options).result()
                    .get(5, TimeUnit.SECONDS).stopReason());
            var error = provider.stream(model, context, options).result().get(5, TimeUnit.SECONDS);
            assertTrue(error.errorMessage().contains("overloaded"));
            var ready = new CountDownLatch(1);
            server.enqueue(exchange -> {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(event("{\"type\":\"message_start\",\"message\":{}}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                ready.countDown();
                Thread.sleep(5000);
            });
            var cancellation = new CancellationToken();
            var stream = provider.stream(model, context, options.withCancellation(cancellation));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            cancellation.cancel();
            assertEquals(StopReason.ABORTED, stream.result().get(5, TimeUnit.SECONDS).stopReason());
        }
    }
}
