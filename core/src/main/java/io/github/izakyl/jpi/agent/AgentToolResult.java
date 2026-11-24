package io.github.izakyl.jpi.agent;

import com.google.gson.JsonElement;
import io.github.izakyl.jpi.ai.ContentBlock;
import java.util.List;

public record AgentToolResult(List<ContentBlock> content, JsonElement details, boolean isError) {

    public AgentToolResult {
        content = List.copyOf(content);
        details = details == null ? null : details.deepCopy();
        if (content.stream().anyMatch(b -> !(b instanceof ContentBlock.Text || b instanceof ContentBlock.Image))) {
            throw new IllegalArgumentException("Tool results support text and images only");
        }
    }

    public AgentToolResult(List<ContentBlock> content, JsonElement details) {
        this(content, details, false);
    }

    @Override
    public JsonElement details() {
        return details == null ? null : details.deepCopy();
    }

    public static AgentToolResult text(String text) {
        return new AgentToolResult(List.of(new ContentBlock.Text(text)), null);
    }

    public static AgentToolResult error(String text) {
        return new AgentToolResult(List.of(new ContentBlock.Text(text)), null, true);
    }
}
