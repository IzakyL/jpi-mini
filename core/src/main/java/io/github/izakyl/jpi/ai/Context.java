package io.github.izakyl.jpi.ai;

import java.util.List;

public record Context(String systemPrompt, List<Message> messages, List<Tool> tools) {

    public Context {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
    }

    public Context(String systemPrompt, List<Message> messages) {
        this(systemPrompt, messages, List.of());
    }
}
