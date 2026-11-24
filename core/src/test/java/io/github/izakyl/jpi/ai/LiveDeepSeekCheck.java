package io.github.izakyl.jpi.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.izakyl.jpi.agent.Agent;
import io.github.izakyl.jpi.agent.AgentTool;
import io.github.izakyl.jpi.agent.AgentToolResult;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit opt-in live check; credentials are read only from the environment. */
@EnabledIfEnvironmentVariable(named = "JPI_LIVE_DEEPSEEK_KEY", matches = ".+")
class LiveDeepSeekCheck {

    @Test
    void twoTurnToolAgent() throws Exception {
        var executions = new AtomicInteger();
        AgentTool tool = new AgentTool() {

            @Override
            public String name() {
                return "read_verification_code";
            }

            @Override
            public String label() {
                return "Verification code";
            }

            @Override
            public String description() {
                return "Read the verification code required to answer the user.";
            }

            @Override
            public JsonObject parameters() {
                var schema = new JsonObject();
                schema.addProperty("type", "object");
                schema.add("properties", new JsonObject());
                schema.addProperty("additionalProperties", false);
                return schema;
            }

            @Override
            public AgentToolResult execute(String id, JsonObject args, CancellationToken cancellation,
                    Consumer<AgentToolResult> update) {
                cancellation.throwIfCancelled();
                executions.incrementAndGet();
                return AgentToolResult.text("JPI-7429");
            }
        };
        var options = StreamOptions.builder().apiKey(System.getenv("JPI_LIVE_DEEPSEEK_KEY"))
                .maxTokens(4096).build();
        var agent = new Agent(Model.deepseek("deepseek-v4-flash"),
                "Call read_verification_code exactly once, then report the code. Keep the answer short.",
                List.of(tool), options);
        var messages = agent.prompt("Read and report the verification code.").get(180, TimeUnit.SECONDS);
        var assistants = messages.stream().filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast).toList();
        // Only fixed transcript facts are printed; provider errors can contain sensitive request data.
        System.out.println("DeepSeek live: assistant turns=" + assistants.size() + ", tool executions="
                + executions.get() + ", outcomes="
                + assistants.stream().map(AssistantMessage::stopReason).toList());
        assertEquals(2, assistants.size());
        assertEquals(1, executions.get());
        assertEquals(StopReason.TOOL_USE, assistants.get(0).stopReason());
        assertEquals(StopReason.STOP, assistants.get(1).stopReason());
        assertFalse(assistants.get(1).text().isBlank());
        assertTrue(assistants.get(1).text().contains("JPI-7429"));
        System.out.println("DeepSeek live: tool returned JPI-7429, "
                + "final answer contained JPI-7429");
    }
}
