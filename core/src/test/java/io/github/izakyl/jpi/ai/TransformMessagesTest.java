package io.github.izakyl.jpi.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransformMessagesTest {

    @Test
    void convertsCrossModelHistoryAndPairsCalls() {
        var target = Model.builder("target", "http://localhost").input(List.of("text")).build();
        var old = new AssistantMessage(List.of(new ContentBlock.Thinking("hidden", "signature"),
                new ContentBlock.Text("answer", "signature"),
                new ContentBlock.ToolCall("call|provider-id", "weather", new JsonObject())),
                "old-api", "old-provider", "old-model", Usage.ZERO, StopReason.TOOL_USE, null, 1);
        var result = new ToolResultMessage("call|provider-id", "weather",
                List.of(new ContentBlock.Image("aA==", "image/png")), null, false, 2);
        var converted = TransformMessages.apply(target, List.of(old, result));
        var assistant = (AssistantMessage) converted.get(0);
        assertFalse(assistant.content().stream().anyMatch(ContentBlock.Thinking.class::isInstance));
        assertEquals(null, ((ContentBlock.Text) assistant.content().get(0)).signature());
        assertEquals("call", assistant.toolCalls().get(0).id());
        var toolResult = (ToolResultMessage) converted.get(1);
        assertEquals("call", toolResult.toolCallId());
        assertTrue(toolResult.content().get(0) instanceof ContentBlock.Text);
    }

    @Test
    void synthesizesMissingToolResults() {
        var model = Model.builder("test", "http://localhost").build();
        var old = new AssistantMessage(List.of(new ContentBlock.ToolCall("id", "tool", new JsonObject())),
                model.api(), model.provider(), model.id(), Usage.ZERO, StopReason.TOOL_USE, null, 1);
        var converted = TransformMessages.apply(model, List.of(old, new UserMessage("next")));
        assertTrue(converted.get(1) instanceof ToolResultMessage);
        assertTrue(((ToolResultMessage) converted.get(1)).isError());
    }
}
