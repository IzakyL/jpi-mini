package io.github.izakyl.jpi.ai;

import java.util.List;

public sealed interface Message permits UserMessage, AssistantMessage, ToolResultMessage {

    List<ContentBlock> content();

    long timestamp();
}
