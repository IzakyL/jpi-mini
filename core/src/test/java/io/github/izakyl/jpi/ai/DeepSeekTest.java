package io.github.izakyl.jpi.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.izakyl.jpi.FakeServer;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DeepSeekTest {

    @Test
    void toolConversationReplaysReasoningAndRequiredContent() throws Exception {
        try (var server = new FakeServer()) {
            server.fixture("deepseek-tool");
            server.fixture("final");
            server.fixture("final");
            var model = Model.builder("deepseek-v4-flash", server.baseUrl()).provider("deepseek")
                    .reasoning(true).compat(OpenAiCompat.deepseek()).build();
            var registry = ProviderRegistry.defaults();
            var options = StreamOptions.builder().toolChoice("auto").build();
            var messages = new ArrayList<Message>();
            messages.add(new UserMessage("Weather in Paris?"));
            var tool = new Tool("weather", "Read weather", new JsonObject());
            var first = registry.stream(model, new Context("Use tools", messages, List.of(tool)), options)
                    .result().get(5, TimeUnit.SECONDS);
            assertEquals(StopReason.TOOL_USE, first.stopReason());
            messages.add(first);
            messages.add(new ToolResultMessage("call_weather", "weather",
                    List.of(new ContentBlock.Text("sunny")), null, false, 0));
            var second = registry.stream(model, new Context("Use tools", messages, List.of(tool)), options)
                    .result().get(5, TimeUnit.SECONDS);
            assertEquals(StopReason.STOP, second.stopReason());
            var body = server.requests.get(1);
            var replay = body.getAsJsonArray("messages").get(2).getAsJsonObject();
            assertEquals("I should check the weather.", replay.get("reasoning_content").getAsString());
            assertEquals("", replay.get("content").getAsString());
            assertTrue(body.has("max_tokens"));
            assertEquals("enabled", body.getAsJsonObject("thinking").get("type").getAsString());
            for (String field : List.of("store", "tool_choice", "max_completion_tokens")) {
                assertFalse(body.has(field));
            }
            assertEquals("system", body.getAsJsonArray("messages").get(0)
                    .getAsJsonObject().get("role").getAsString());
            messages.add(second);
            messages.add(new UserMessage("Again"));
            registry.stream(model, new Context("", messages), StreamOptions.builder().toolChoice("weather").build())
                    .result().get(5, TimeUnit.SECONDS);
            var third = server.requests.get(2);
            assertEquals("", third.getAsJsonArray("messages").get(3)
                    .getAsJsonObject().get("reasoning_content").getAsString());
            assertEquals("weather", third.getAsJsonObject("tool_choice")
                    .getAsJsonObject("function").get("name").getAsString());
            assertEquals(List.of(), server.failures);
        }
    }

    @Test
    void stripsSplitSpecialTokensAndDsmlFromStreamingText() throws Exception {
        try (var server = new FakeServer()) {
            server.status(200, """
                    data: {"choices":[{"delta":{"content":"Hello<｜end▁of"}}]}

                    data: {"choices":[{"delta":{"content":"▁sentence｜><｜DSML｜function_"}}]}

                    data: {"choices":[{"delta":{"content":"calls><｜DSML｜invoke>leak</｜DSML｜invoke>"}}]}

                    data: {"choices":[{"delta":{"content":"</｜DSML｜function_calls> world"},"finish_reason":"stop"}]}

                    data: [DONE]

                    """);
            var model = Model.builder("fake", server.baseUrl()).compat(OpenAiCompat.deepseek()).build();
            var stream = new OpenAiCompletions(HttpClient.newHttpClient()).stream(model,
                    new Context("", List.of(new UserMessage("hi"))), StreamOptions.defaults());
            assertEquals("Hello world", stream.result().get(5, TimeUnit.SECONDS).text());
        }
    }
}
