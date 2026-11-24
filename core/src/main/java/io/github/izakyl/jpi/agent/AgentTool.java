package io.github.izakyl.jpi.agent;

import com.google.gson.JsonObject;
import io.github.izakyl.jpi.ai.CancellationToken;
import io.github.izakyl.jpi.ai.Tool;
import java.util.function.Consumer;

public interface AgentTool {

    String name();

    String label();

    String description();

    JsonObject parameters();

    AgentToolResult execute(String toolCallId, JsonObject arguments, CancellationToken cancellation,
            Consumer<AgentToolResult> onUpdate) throws Exception;

    default Tool declaration() {
        return new Tool(name(), description(), parameters());
    }
}
