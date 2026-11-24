package io.github.izakyl.jpi.ai;

import static io.github.izakyl.jpi.ai.AssistantMessageEvent.Kind.TEXT;
import static io.github.izakyl.jpi.ai.AssistantMessageEvent.Kind.THINKING;
import static io.github.izakyl.jpi.ai.AssistantMessageEvent.Kind.TOOLCALL;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.izakyl.jpi.ai.internal.StrictJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Responses SSE state keyed by output index, including interleaved function argument streams. */
final class OpenAiResponsesStream {

    private final Model model;

    private final AssistantMessageEventStream stream;

    private final List<ContentBlock> content = new ArrayList<>();

    private final Map<Integer, Slot> slots = new LinkedHashMap<>();

    private final long timestamp = System.currentTimeMillis();

    private Usage usage = Usage.ZERO;

    private StopReason reason = StopReason.STOP;

    private String error;

    private boolean terminal;

    OpenAiResponsesStream(Model model, AssistantMessageEventStream stream) {
        this.model = model;
        this.stream = stream;
    }

    AssistantMessage snapshot() {
        return new AssistantMessage(
                content, model.api(), model.provider(), model.id(), usage, reason, error, timestamp);
    }

    void start() {
        stream.publish(new AssistantMessageEvent.Start(snapshot()));
    }

    void fail(StopReason stop, String message) {
        reason = stop;
        error = message;
    }

    boolean event(String data) {
        if (data.trim().equals("[DONE]")) {
            return true;
        }
        var event = StrictJson.parse(data).getAsJsonObject();
        String type = string(event, "type");
        switch (type) {
            case "response.output_item.added" -> create(index(event), object(event, "item"));
            case "response.output_text.delta", "response.refusal.delta" ->
                    append(slot(event, TEXT), string(event, "delta"));
            case "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                    append(slot(event, THINKING), string(event, "delta"));
            case "response.reasoning_summary_part.done" -> append(slot(event, THINKING), "\n\n");
            case "response.function_call_arguments.delta" -> arguments(slot(event, TOOLCALL), string(event, "delta"));
            case "response.function_call_arguments.done" -> replaceArguments(slot(event, TOOLCALL),
                    string(event, "arguments"));
            case "response.output_item.done" -> end(index(event), object(event, "item"));
            case "response.done", "response.completed", "response.incomplete" -> {
                complete(object(event, "response"), type);
                return true;
            }
            case "response.failed" -> throw new IllegalStateException(providerError(
                    object(object(event, "response"), "error"), "Responses API response failed"));
            case "error" -> throw new IllegalStateException(providerError(
                    object(event, "error") == null ? event : object(event, "error"), "Responses API stream error"));
            default -> {
                // Lifecycle and content-part events carry no additional deltas.
            }
        }
        return false;
    }

    private void create(int outputIndex, JsonObject item) {
        if (item == null || slots.containsKey(outputIndex)) {
            throw new IllegalStateException("Missing or duplicate Responses output item");
        }
        AssistantMessageEvent.Kind kind;
        ContentBlock block;
        switch (string(item, "type")) {
            case "message" -> {
                kind = TEXT;
                block = new ContentBlock.Text("");
            }
            case "reasoning" -> {
                kind = THINKING;
                block = new ContentBlock.Thinking("");
            }
            case "function_call" -> {
                kind = TOOLCALL;
                block = new ContentBlock.ToolCall(callId(item), string(item, "name"), new JsonObject());
            }
            default -> {
                return;
            }
        }
        var slot = new Slot(content.size(), kind, item.deepCopy());
        slots.put(outputIndex, slot);
        content.add(block);
        stream.publish(new AssistantMessageEvent.BlockStart(kind, slot.index, snapshot()));
        if (kind == TOOLCALL && !string(item, "arguments").isEmpty()) {
            arguments(slot, string(item, "arguments"));
        }
    }

    private Slot slot(JsonObject event, AssistantMessageEvent.Kind kind) {
        var slot = slots.get(index(event));
        if (slot == null || slot.kind != kind || slot.ended) {
            throw new IllegalStateException("Responses delta has no matching open output item");
        }
        String itemId = string(event, "item_id");
        if (!itemId.isEmpty() && !itemId.equals(string(slot.item, "id"))) {
            throw new IllegalStateException("Responses delta item id mismatch");
        }
        return slot;
    }

    private void append(Slot slot, String delta) {
        if (slot.kind == TEXT) {
            var old = (ContentBlock.Text) content.get(slot.index);
            content.set(slot.index, new ContentBlock.Text(old.text() + delta, old.signature()));
        } else {
            var old = (ContentBlock.Thinking) content.get(slot.index);
            content.set(slot.index, new ContentBlock.Thinking(old.thinking() + delta, old.signature()));
        }
        stream.publish(new AssistantMessageEvent.Delta(slot.kind, slot.index, delta, snapshot()));
    }

    private void arguments(Slot slot, String delta) {
        slot.arguments.append(delta);
        var parsed = slot.parser.append(delta);
        content.set(slot.index, new ContentBlock.ToolCall(callId(slot.item), string(slot.item, "name"), parsed));
        stream.publish(new AssistantMessageEvent.Delta(TOOLCALL, slot.index, delta, snapshot()));
    }

    private void replaceArguments(Slot slot, String value) {
        String previous = slot.arguments.toString();
        if (previous.equals(value)) {
            return;
        }
        slot.arguments.setLength(0);
        slot.parser = new StreamingJson();
        slot.arguments.append(value);
        var partial = slot.parser.append(value);
        content.set(slot.index, new ContentBlock.ToolCall(callId(slot.item), string(slot.item, "name"), partial));
        String delta = value.startsWith(previous) ? value.substring(previous.length()) : value;
        stream.publish(new AssistantMessageEvent.Delta(TOOLCALL, slot.index, delta, snapshot()));
    }

    private void end(int outputIndex, JsonObject item) {
        var slot = slots.get(outputIndex);
        if (slot == null) {
            create(outputIndex, item);
            slot = slots.get(outputIndex);
        }
        if (slot == null) {
            return;
        }
        if (slot.ended || item == null || !string(slot.item, "id").equals(string(item, "id"))
                || !string(slot.item, "type").equals(string(item, "type"))) {
            throw new IllegalStateException("Responses output item completion mismatch");
        }
        slot.item = item.deepCopy();
        if (slot.kind == TOOLCALL) {
            if (item.has("arguments")) {
                replaceArguments(slot, string(item, "arguments"));
            }
            var parsed = slot.parser.finish();
            content.set(slot.index, new ContentBlock.ToolCall(callId(item), string(item, "name"),
                    parsed.arguments(), parsed.error()));
        } else if (slot.kind == THINKING) {
            String finalText = joined(item, "summary", "\n\n");
            if (finalText.isEmpty()) {
                finalText = joined(item, "content", "\n\n");
            }
            var old = (ContentBlock.Thinking) content.get(slot.index);
            content.set(slot.index, new ContentBlock.Thinking(
                    finalText.isEmpty() ? old.thinking() : finalText, item.toString()));
        } else {
            var old = (ContentBlock.Text) content.get(slot.index);
            String finalText = item.has("content") ? joined(item, "content", "") : old.text();
            var metadata = new JsonObject();
            metadata.addProperty("type", "message");
            metadata.addProperty("id", string(item, "id"));
            if (item.has("phase")) {
                metadata.add("phase", item.get("phase"));
            }
            content.set(slot.index, new ContentBlock.Text(finalText, metadata.toString()));
        }
        slot.ended = true;
        stream.publish(new AssistantMessageEvent.BlockEnd(slot.kind, slot.index, snapshot()));
    }

    private void complete(JsonObject response, String eventType) {
        if (response == null) {
            throw new IllegalStateException("Missing terminal Responses payload");
        }
        terminal = true;
        if (object(response, "usage") != null) {
            usage = usage(object(response, "usage"));
        }
        backfillReasoning(response.getAsJsonArray("output"));
        String status = string(response, "status");
        if (status.isEmpty()) {
            status = "response.incomplete".equals(eventType) ? "incomplete" : "completed";
        }
        switch (status) {
            case "completed" -> reason = content.stream().anyMatch(ContentBlock.ToolCall.class::isInstance)
                    ? StopReason.TOOL_USE : StopReason.STOP;
            case "incomplete" -> {
                String detail = string(object(response, "incomplete_details"), "reason");
                if (detail.equals("max_output_tokens")) {
                    reason = StopReason.LENGTH;
                } else {
                    fail(StopReason.ERROR, "Incomplete Responses API response: " + detail);
                }
            }
            case "cancelled" -> fail(StopReason.ABORTED, "Responses API response cancelled");
            case "failed" -> throw new IllegalStateException(
                    providerError(object(response, "error"), "Responses API response failed"));
            default -> fail(StopReason.ERROR, "Unexpected Responses API response status: " + status);
        }
    }

    private void backfillReasoning(JsonArray output) {
        if (output == null) {
            return;
        }
        for (JsonElement element : output) {
            var item = element.getAsJsonObject();
            if (!"reasoning".equals(string(item, "type")) || string(item, "encrypted_content").isEmpty()) {
                continue;
            }
            for (var slot : slots.values()) {
                if (slot.kind == THINKING && string(slot.item, "id").equals(string(item, "id"))) {
                    var old = (ContentBlock.Thinking) content.get(slot.index);
                    var signature = slot.item.deepCopy();
                    signature.add("encrypted_content", item.get("encrypted_content"));
                    content.set(slot.index, new ContentBlock.Thinking(old.thinking(), signature.toString()));
                }
            }
        }
    }

    void finish() {
        if (!terminal) {
            throw new IllegalStateException("Responses stream ended before a terminal response event");
        }
        for (var slot : slots.values()) {
            if (!slot.ended && slot.kind == TOOLCALL && reason == StopReason.TOOL_USE) {
                throw new IllegalStateException("Responses stream completed with an unfinished tool call");
            }
            if (!slot.ended) {
                stream.publish(new AssistantMessageEvent.BlockEnd(slot.kind, slot.index, snapshot()));
                slot.ended = true;
            }
        }
    }

    private Usage usage(JsonObject raw) {
        long prompt = number(raw, "input_tokens");
        var details = object(raw, "input_tokens_details");
        long cached = number(details, "cached_tokens");
        long written = number(details, "cache_write_tokens");
        long input = Math.max(0, prompt - cached - written);
        long output = number(raw, "output_tokens");
        long total = raw.has("total_tokens") ? number(raw, "total_tokens") : prompt + output;
        var cost = Usage.Cost.ZERO;
        if (model.cost() != null) {
            double inputCost = input * model.cost().input() / 1_000_000;
            double outputCost = output * model.cost().output() / 1_000_000;
            double readCost = cached * model.cost().cacheRead() / 1_000_000;
            double writeCost = written * model.cost().cacheWrite() / 1_000_000;
            cost = new Usage.Cost(inputCost, outputCost, readCost, writeCost,
                    inputCost + outputCost + readCost + writeCost);
        }
        return new Usage(input, output, cached, written,
                number(object(raw, "output_tokens_details"), "reasoning_tokens"), total, cost);
    }

    private static int index(JsonObject event) {
        if (!event.has("output_index") || !event.get("output_index").isJsonPrimitive()
                || !event.get("output_index").getAsJsonPrimitive().isNumber()) {
            throw new IllegalStateException("Responses event missing output_index");
        }
        return event.get("output_index").getAsInt();
    }

    private static String callId(JsonObject item) {
        String call = string(item, "call_id");
        String id = string(item, "id");
        if (call.isEmpty() || id.isEmpty() || string(item, "name").isEmpty()) {
            throw new IllegalStateException("Responses function call is missing identifiers");
        }
        return call + "|" + id;
    }

    private static String joined(JsonObject item, String field, String delimiter) {
        var parts = item.getAsJsonArray(field);
        if (parts == null) {
            return "";
        }
        return parts.asList().stream().map(JsonElement::getAsJsonObject)
                .map(part -> part.has("text") ? string(part, "text") : string(part, "refusal"))
                .collect(Collectors.joining(delimiter));
    }

    private static String providerError(JsonObject error, String fallback) {
        String code = string(error, "code");
        String message = string(error, "message");
        return message.isEmpty() && code.isEmpty() ? fallback : code + ": " + message;
    }

    private static String string(JsonObject object, String key) {
        var value = object == null ? null : object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static long number(JsonObject object, String key) {
        var value = object == null ? null : object.get(key);
        return value == null || value.isJsonNull() ? 0 : value.getAsLong();
    }

    private static JsonObject object(JsonObject object, String key) {
        var value = object == null ? null : object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static final class Slot {

        private final int index;

        private final AssistantMessageEvent.Kind kind;

        private JsonObject item;

        private StreamingJson parser = new StreamingJson();

        private final StringBuilder arguments = new StringBuilder();

        private boolean ended;

        private Slot(int index, AssistantMessageEvent.Kind kind, JsonObject item) {
            this.index = index;
            this.kind = kind;
            this.item = item;
        }
    }
}
