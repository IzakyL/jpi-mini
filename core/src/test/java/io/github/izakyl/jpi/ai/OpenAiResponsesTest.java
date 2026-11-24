package io.github.izakyl.jpi.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.izakyl.jpi.FakeServer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OpenAiResponsesTest {

    @Test
    void streamsAndSendsOrdinaryResponsesRequest() throws Exception {
        try (var server = new FakeServer("/v1/responses")) {
            server.fixture("responses-final");
            var model = Model.builder("fixture", server.baseUrl()).api(OpenAiResponses.API)
                    .headers(Map.of("X-Model", "model")).build();
            var options = StreamOptions.builder().apiKey("secret").maxTokens(256)
                    .headers(Map.of("X-Request", "request")).build();
            var context = new Context("Help", List.of(new UserMessage("Hello")));
            var result = ProviderRegistry.defaults().stream(model, context, options).result().get(5, TimeUnit.SECONDS);
            assertEquals(StopReason.STOP, result.stopReason());
            assertEquals("It is sunny.", result.text());
            assertEquals("Bearer secret", server.authorization.get(0));
            assertEquals(256, server.requests.get(0).get("max_output_tokens").getAsInt());
            assertTrue(server.failures.isEmpty());
        }
    }

    @Test
    void environmentResolverIsExplicitAndProviderSpecific() {
        var resolver = new EnvApiKeyResolver(Map.of("DEEPSEEK_API_KEY", "key"));
        assertEquals("key", resolver.resolve(Model.deepseek("test")));
        assertEquals(null, resolver.resolve(Model.builder("test", "http://localhost").build()));
    }
    @Test
    void detectsCompatibilityAndAllowsExplicitOverride() {
        var inferred = Model.builder("test", "https://api.moonshot.cn/v1").build();
        assertEquals("max_tokens", inferred.compat().maxTokensField());
        assertFalse(inferred.compat().supportsDeveloperRole());
        var override = Model.builder("test", "https://api.moonshot.cn/v1")
                .compat(OpenAiCompat.defaults()).build();
        assertEquals("max_completion_tokens", override.compat().maxTokensField());
        assertTrue(Model.builder("test", "https://api.deepseek.com").build()
                .compat().requiresReasoningContentForToolCalls());
    }

    @Test
    void streamsToolAndThinkingAndReplaysImageAndReasoning() throws Exception {
        try (var server = new FakeServer("/v1/responses")) {
            server.fixture("responses-tool");
            server.fixture("responses-final");
            var model = Model.builder("fixture", server.baseUrl()).api(OpenAiResponses.API)
                    .reasoning(true).input(List.of("text", "image")).build();
            var image = new UserMessage(List.of(new ContentBlock.Image("aGVsbG8=", "image/png")), 1);
            var registry = ProviderRegistry.defaults();
            var options = new StreamOptions("secret");
            var first = registry.stream(model, new Context(null, List.of(image)), options)
                    .result().get(5, TimeUnit.SECONDS);
            assertEquals(StopReason.TOOL_USE, first.stopReason());
            assertEquals("Paris", first.toolCalls().get(0).arguments().get("city").getAsString());
            assertTrue(first.content().stream().anyMatch(ContentBlock.Thinking.class::isInstance));
            assertEquals("data:image/png;base64,aGVsbG8=", server.requests.get(0).getAsJsonArray("input")
                    .get(0).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject()
                    .get("image_url").getAsString());
            var second = registry.stream(model, new Context(null, List.of(image, first)), options)
                    .result().get(5, TimeUnit.SECONDS);
            assertEquals(StopReason.STOP, second.stopReason());
            assertEquals("opaque-reasoning", server.requests.get(1).getAsJsonArray("input").get(1)
                    .getAsJsonObject().get("encrypted_content").getAsString());
            assertTrue(server.failures.isEmpty());
        }
    }

    @Test
    void reportsHttpFailure() throws Exception {
        try (var server = new FakeServer("/v1/responses")) {
            server.status(401, "unauthorized");
            var model = Model.builder("fixture", server.baseUrl()).api(OpenAiResponses.API).build();
            var result = ProviderRegistry.defaults().stream(model, new Context(null, List.of()),
                    new StreamOptions("secret")).result().get(5, TimeUnit.SECONDS);
            assertEquals(StopReason.ERROR, result.stopReason());
            assertTrue(result.errorMessage().contains("401"));
        }
    }

}
