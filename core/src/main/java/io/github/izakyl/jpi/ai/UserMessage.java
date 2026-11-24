package io.github.izakyl.jpi.ai;

import java.util.List;

public record UserMessage(List<ContentBlock> content, long timestamp) implements Message {

    public UserMessage {
        content = List.copyOf(content);
        if (content.stream().anyMatch(b -> !(b instanceof ContentBlock.Text || b instanceof ContentBlock.Image))) {
            throw new IllegalArgumentException("User messages support text and images only");
        }
    }

    public UserMessage(String text) {
        this(List.of(new ContentBlock.Text(text)), System.currentTimeMillis());
    }
}
