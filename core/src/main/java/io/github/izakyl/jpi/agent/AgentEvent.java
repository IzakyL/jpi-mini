package io.github.izakyl.jpi.agent;

import io.github.izakyl.jpi.ai.AssistantMessage;
import io.github.izakyl.jpi.ai.AssistantMessageEvent;
import io.github.izakyl.jpi.ai.ContentBlock;
import io.github.izakyl.jpi.ai.Message;
import io.github.izakyl.jpi.ai.ToolResultMessage;
import java.util.List;

public sealed interface AgentEvent {

    String type();

    record AgentStart() implements AgentEvent {

        @Override
        public String type() {
            return "agent_start";
        }
    }

    record AgentEnd(List<Message> messages) implements AgentEvent {

        public AgentEnd {
            messages = List.copyOf(messages);
        }

        @Override
        public String type() {
            return "agent_end";
        }
    }

    record TurnStart() implements AgentEvent {

        @Override
        public String type() {
            return "turn_start";
        }
    }

    record TurnEnd(AssistantMessage message, List<ToolResultMessage> toolResults) implements AgentEvent {

        public TurnEnd {
            toolResults = List.copyOf(toolResults);
        }

        @Override
        public String type() {
            return "turn_end";
        }
    }

    record MessageStart(Message message) implements AgentEvent {

        @Override
        public String type() {
            return "message_start";
        }
    }

    record MessageUpdate(AssistantMessage message, AssistantMessageEvent assistantMessageEvent) implements AgentEvent {

        @Override
        public String type() {
            return "message_update";
        }
    }

    record MessageEnd(Message message) implements AgentEvent {

        @Override
        public String type() {
            return "message_end";
        }
    }

    record ToolExecutionStart(ContentBlock.ToolCall toolCall) implements AgentEvent {

        @Override
        public String type() {
            return "tool_execution_start";
        }
    }

    record ToolExecutionUpdate(ContentBlock.ToolCall toolCall, AgentToolResult partialResult) implements AgentEvent {

        @Override
        public String type() {
            return "tool_execution_update";
        }
    }

    record ToolExecutionEnd(ContentBlock.ToolCall toolCall, AgentToolResult result) implements AgentEvent {

        @Override
        public String type() {
            return "tool_execution_end";
        }
    }
}
