package io.github.izakyl.jpi.agent;

import io.github.izakyl.jpi.ai.AssistantMessage;
import io.github.izakyl.jpi.ai.Message;
import io.github.izakyl.jpi.ai.Model;
import java.util.List;

public record AgentState(String systemPrompt, Model model, List<AgentTool> tools, List<Message> messages,
        boolean isStreaming, AssistantMessage streamingMessage) {

    public AgentState {
        tools = List.copyOf(tools);
        messages = List.copyOf(messages);
    }
}
