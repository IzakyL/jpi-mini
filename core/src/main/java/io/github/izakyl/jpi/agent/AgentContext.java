package io.github.izakyl.jpi.agent;

import io.github.izakyl.jpi.ai.Message;
import java.util.HashSet;
import java.util.List;

public record AgentContext(String systemPrompt, List<Message> messages, List<AgentTool> tools) {

    public AgentContext {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        var names = new HashSet<String>();
        for (var tool : tools) {
            if (!names.add(tool.name())) {
                throw new IllegalArgumentException("Duplicate tool name: " + tool.name());
            }
            tool.declaration();
        }
    }
}
