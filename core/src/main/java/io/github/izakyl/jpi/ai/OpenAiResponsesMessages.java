package io.github.izakyl.jpi.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.izakyl.jpi.ai.internal.StrictJson;
import java.util.List;
import java.util.stream.Collectors;

/** Stateless Responses history conversion, retaining same-model reasoning and call pairing. */
final class OpenAiResponsesMessages {

    private OpenAiResponsesMessages() {
    }

    static JsonArray convert(Model model, List<Message> messages) {
        var input = new JsonArray();
        int index = 0;
        for (var message : messages) {
            if (message instanceof UserMessage user) {
                var item = new JsonObject();
                item.addProperty("role", "user");
                item.add("content", inputContent(user.content()));
                input.add(item);
            } else if (message instanceof AssistantMessage assistant && !assistant.stopReason().isFailure()) {
                assistant(input, model, assistant, index);
            } else if (message instanceof ToolResultMessage result) {
                var item = new JsonObject();
                item.addProperty("type", "function_call_output");
                item.addProperty("call_id", callId(result.toolCallId()));
                if (result.content().stream().anyMatch(ContentBlock.Image.class::isInstance)) {
                    item.add("output", inputContent(result.content()));
                } else {
                    item.addProperty("output", result.content().stream().filter(ContentBlock.Text.class::isInstance)
                            .map(ContentBlock.Text.class::cast).map(ContentBlock.Text::text)
                            .collect(Collectors.joining("\n")));
                }
                input.add(item);
            }
            index++;
        }
        return input;
    }

    private static void assistant(JsonArray input, Model model, AssistantMessage assistant, int index) {
        boolean sameApi = model.api().equals(assistant.api()) && model.provider().equals(assistant.provider());
        boolean sameModel = sameApi && model.id().equals(assistant.model());
        int blockIndex = 0;
        for (var block : assistant.content()) {
            if (block instanceof ContentBlock.Thinking thinking && sameModel) {
                var signature = signature(thinking.signature(), "reasoning");
                if (signature != null) {
                    input.add(signature);
                }
            } else if (block instanceof ContentBlock.Text text) {
                var item = new JsonObject();
                item.addProperty("type", "message");
                item.addProperty("role", "assistant");
                item.addProperty("status", "completed");
                var metadata = sameApi ? signature(text.signature(), "message") : null;
                String id = metadata == null ? "" : string(metadata, "id");
                item.addProperty("id", id.isEmpty() || id.length() > 64 ? "msg_jpi_" + index + "_" + blockIndex : id);
                if (metadata != null && metadata.has("phase")) {
                    item.add("phase", metadata.get("phase"));
                }
                var content = new JsonArray();
                var part = new JsonObject();
                part.addProperty("type", "output_text");
                part.addProperty("text", text.text());
                part.add("annotations", new JsonArray());
                content.add(part);
                item.add("content", content);
                input.add(item);
            } else if (block instanceof ContentBlock.ToolCall call) {
                var item = new JsonObject();
                item.addProperty("type", "function_call");
                item.addProperty("call_id", callId(call.id()));
                String[] ids = call.id().split("\\|", 2);
                if (sameModel && ids.length == 2 && ids[1].startsWith("fc_") && ids[1].length() <= 64) {
                    item.addProperty("id", ids[1]);
                }
                item.addProperty("name", call.name());
                item.addProperty("arguments", call.arguments().toString());
                input.add(item);
            }
            blockIndex++;
        }
    }

    private static JsonArray inputContent(List<ContentBlock> blocks) {
        var result = new JsonArray();
        for (var block : blocks) {
            var part = new JsonObject();
            if (block instanceof ContentBlock.Text text) {
                part.addProperty("type", "input_text");
                part.addProperty("text", text.text());
            } else if (block instanceof ContentBlock.Image image) {
                part.addProperty("type", "input_image");
                part.addProperty("detail", "auto");
                part.addProperty("image_url", "data:" + image.mimeType() + ";base64," + image.data());
            } else {
                continue;
            }
            result.add(part);
        }
        return result;
    }

    private static JsonObject signature(String value, String type) {
        if (value == null || !value.startsWith("{")) {
            return null;
        }
        try {
            JsonElement parsed = StrictJson.parse(value);
            if (parsed.isJsonObject() && type.equals(string(parsed.getAsJsonObject(), "type"))) {
                return parsed.getAsJsonObject();
            }
        } catch (RuntimeException ignored) {
            // Other providers can use non-JSON reasoning signatures.
        }
        return null;
    }

    private static String callId(String id) {
        String value = id.split("\\|", 2)[0].replaceAll("[^A-Za-z0-9_-]", "_");
        return value.length() <= 64 ? value : value.substring(0, 48) + "_" + Integer.toHexString(value.hashCode());
    }

    private static String string(JsonObject object, String key) {
        var value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
