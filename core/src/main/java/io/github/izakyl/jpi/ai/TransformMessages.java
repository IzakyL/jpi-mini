package io.github.izakyl.jpi.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class TransformMessages {

    private TransformMessages() {
    }

    static List<Message> apply(Model model, List<Message> messages) {
        var output = new ArrayList<Message>();
        Map<String, String> ids = new HashMap<>();
        var pending = new ArrayList<ContentBlock.ToolCall>();
        for (var message : messages) {
            if (!(message instanceof ToolResultMessage)) {
                closePending(output, pending);
            }
            if (message instanceof UserMessage user) {
                output.add(new UserMessage(
                        images(model, user.content(), "(image omitted: model does not support images)"),
                        user.timestamp()));
            } else if (message instanceof AssistantMessage assistant) {
                if (assistant.stopReason().isFailure()) {
                    continue;
                }
                boolean same = model.id().equals(assistant.model()) && model.api().equals(assistant.api())
                        && model.provider().equals(assistant.provider());
                var content = new ArrayList<ContentBlock>();
                for (var block : assistant.content()) {
                    if (block instanceof ContentBlock.Thinking && !same) {
                        continue;
                    }
                    if (block instanceof ContentBlock.Text text && !same) {
                        block = new ContentBlock.Text(text.text());
                    }
                    if (block instanceof ContentBlock.ToolCall call) {
                        String id = same ? call.id() : normalize(call.id());
                        ids.put(call.id(), id);
                        block = new ContentBlock.ToolCall(id, call.name(), call.arguments(), call.argumentsError(),
                                same ? call.signature() : null);
                        pending.add((ContentBlock.ToolCall) block);
                    }
                    content.add(block);
                }
                output.add(new AssistantMessage(content, assistant.api(), assistant.provider(), assistant.model(),
                        assistant.usage(), assistant.stopReason(), assistant.errorMessage(), assistant.timestamp()));
            } else if (message instanceof ToolResultMessage result) {
                String id = ids.getOrDefault(result.toolCallId(), result.toolCallId());
                pending.removeIf(call -> call.id().equals(id));
                output.add(new ToolResultMessage(id, result.toolName(),
                        images(model, result.content(), "(tool image omitted: model does not support images)"),
                        result.details(), result.isError(), result.timestamp()));
            }
        }
        closePending(output, pending);
        return List.copyOf(output);
    }

    private static List<ContentBlock> images(Model model, List<ContentBlock> content, String placeholder) {
        if (model.input().contains("image")) {
            return content;
        }
        var result = new ArrayList<ContentBlock>();
        for (var block : content) {
            if (block instanceof ContentBlock.Image) {
                if (result.isEmpty() || !(result.get(result.size() - 1) instanceof ContentBlock.Text text)
                        || !text.text().equals(placeholder)) {
                    result.add(new ContentBlock.Text(placeholder));
                }
            } else {
                result.add(block);
            }
        }
        return result;
    }

    private static void closePending(List<Message> output, List<ContentBlock.ToolCall> pending) {
        for (var call : pending) {
            output.add(new ToolResultMessage(call.id(), call.name(),
                    List.of(new ContentBlock.Text("No result provided")),
                    null, true, System.currentTimeMillis()));
        }
        pending.clear();
    }

    private static String normalize(String id) {
        String clean = id.split("\\|", 2)[0].replaceAll("[^a-zA-Z0-9_-]", "_");
        return clean.length() <= 64 ? clean : clean.substring(0, 64);
    }
}
