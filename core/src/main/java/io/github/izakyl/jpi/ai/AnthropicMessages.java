package io.github.izakyl.jpi.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.izakyl.jpi.ai.internal.StrictJson;
import io.github.izakyl.jpi.auth.Credential;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Anthropic Messages backend over SSE. */
public final class AnthropicMessages implements AiProvider {

    public static final String API = "anthropic-messages";

    private final HttpClient client;

    public AnthropicMessages(HttpClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
        var actual = options == null ? StreamOptions.defaults() : options;
        return ProviderStreams.start(model, actual, (stream, budget) -> run(model, context, actual, stream, budget));
    }

    private void run(Model model, Context context, StreamOptions options, AssistantMessageEventStream stream,
            RetryBudget budget) {
        var state = new State(model, stream);
        String token = null;
        try {
            options.cancellation().throwIfCancelled();
            Credential credential = null;
            if (options.apiKey() != null && !options.apiKey().isBlank()) {
                credential = new Credential.ApiKey(options.apiKey());
            } else if (options.apiKeyResolver() != null) {
                String key = options.apiKeyResolver().resolve(model);
                if (key != null && !key.isBlank()) {
                    credential = new Credential.ApiKey(key);
                }
            }
            if (credential == null && options.credentialResolver() != null) {
                credential = options.credentialResolver().resolve(model, options.cancellation());
            }
            var headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            headers.put("Accept", "text/event-stream");
            headers.put("Content-Type", "application/json");
            headers.put("anthropic-version", "2023-06-01");
            if (credential != null) {
                token = credential.apiKey();
                headers.put("x-api-key", token);
            }
            headers.putAll(model.headers());
            headers.putAll(options.headers());
            if (!headers.containsKey("x-api-key") && !headers.containsKey("Authorization")
                    && !headers.containsKey("cf-aig-authorization")) {
                throw new IOException("API key required");
            }
            String base = model.baseUrl().replaceAll("/+$", "");
            var builder = HttpRequest.newBuilder(URI.create(base.endsWith("/messages")
                    ? base : base + (base.endsWith("/v1") ? "/messages" : "/v1/messages")));
            headers.forEach(builder::setHeader);
            var request = builder.POST(HttpRequest.BodyPublishers.ofString(
                    UnicodeSanitizer.sanitize(requestBody(model, context, options).toString()),
                    StandardCharsets.UTF_8)).build();
            read(ProviderTransport.send(client, request, options, budget), options.cancellation(), state);
        } catch (Exception error) {
            Throwable cause = error;
            while (!(cause instanceof IOException) && cause.getCause() != null) {
                cause = cause.getCause();
            }
            String message = cause.getMessage() == null ? "Anthropic request failed" : cause.getMessage();
            if (token != null && !token.isEmpty()) {
                message = message.replace(token, "[redacted]");
            }
            state.fail(options.cancellation().isCancelled() ? StopReason.ABORTED : StopReason.ERROR,
                    options.cancellation().isCancelled() ? "Request was aborted" : message);
        }
        stream.complete(state.snapshot());
    }

    JsonObject requestBody(Model model, Context context, StreamOptions options) {
        var body = new JsonObject();
        body.addProperty("model", model.id());
        body.addProperty("stream", true);
        body.addProperty("max_tokens", options.maxTokens() == null ? model.maxTokens() : options.maxTokens());
        if (context.systemPrompt() != null && !context.systemPrompt().isBlank()) {
            body.addProperty("system", context.systemPrompt());
        }
        if (options.temperature() != null && options.reasoningEffort() == null) {
            body.addProperty("temperature", options.temperature());
        }
        if (model.reasoning() && options.reasoningEffort() != null && !options.reasoningEffort().equals("none")) {
            var thinking = new JsonObject();
            boolean adaptive = model.id().matches(".*(?:opus-4-[67]|sonnet-4-6).*?");
            thinking.addProperty("type", adaptive ? "adaptive" : "enabled");
            if (adaptive) {
                var config = new JsonObject();
                config.addProperty("effort", options.reasoningEffort());
                body.add("output_config", config);
            } else {
                thinking.addProperty("budget_tokens", Math.min(1024, Math.max(1,
                        (options.maxTokens() == null ? model.maxTokens() : options.maxTokens()) - 1)));
            }
            body.add("thinking", thinking);
        }
        if (options.toolChoice() != null) {
            var choice = new JsonObject();
            String value = options.toolChoice();
            if (List.of("auto", "none", "required", "any").contains(value)) {
                choice.addProperty("type", value.equals("required") ? "any" : value);
            } else {
                choice.addProperty("type", "tool");
                choice.addProperty("name", value);
            }
            body.add("tool_choice", choice);
        }
        var tools = new JsonArray();
        for (var tool : context.tools()) {
            var item = new JsonObject();
            item.addProperty("name", tool.name());
            item.addProperty("description", tool.description());
            item.add("input_schema", tool.parameters());
            tools.add(item);
        }
        if (!tools.isEmpty()) {
            body.add("tools", tools);
        }
        var messages = new JsonArray();
        JsonArray results = null;
        var errorImages = new JsonArray();
        for (var message : context.messages()) {
            if (message instanceof ToolResultMessage result) {
                if (results == null) {
                    results = new JsonArray();
                    messages.add(message("user", results));
                }
                var item = new JsonObject();
                item.addProperty("type", "tool_result");
                item.addProperty("tool_use_id", result.toolCallId());
                item.addProperty("is_error", result.isError());
                var resultContent = new ArrayList<ContentBlock>();
                for (var block : result.content()) {
                    if (result.isError() && block instanceof ContentBlock.Image) {
                        errorImages.addAll(blocks(List.of(block), false, false));
                    } else {
                        resultContent.add(block);
                    }
                }
                item.add("content", blocks(resultContent, false, false));
                results.add(item);
                continue;
            }
            if (results != null) {
                results.addAll(errorImages);
                errorImages = new JsonArray();
            }
            results = null;
            if (message instanceof UserMessage user) {
                messages.add(message("user", blocks(user.content(), false, false)));
            } else if (message instanceof AssistantMessage assistant) {
                boolean same = model.api().equals(assistant.api()) && model.provider().equals(assistant.provider())
                        && model.id().equals(assistant.model());
                var content = blocks(assistant.content(), true, same);
                if (!content.isEmpty()) {
                    messages.add(message("assistant", content));
                }
            }
        }
        if (results != null) {
            results.addAll(errorImages);
        }
        body.add("messages", messages);
        return body;
    }

    private static JsonObject message(String role, JsonArray content) {
        var message = new JsonObject();
        message.addProperty("role", role);
        message.add("content", content);
        return message;
    }

    private static JsonArray blocks(List<ContentBlock> content, boolean assistant, boolean same) {
        var result = new JsonArray();
        for (var block : content) {
            var item = new JsonObject();
            if (block instanceof ContentBlock.Text text) {
                item.addProperty("type", "text");
                item.addProperty("text", text.text());
            } else if (block instanceof ContentBlock.Image image) {
                item.addProperty("type", "image");
                var source = new JsonObject();
                source.addProperty("type", "base64");
                source.addProperty("media_type", image.mimeType());
                source.addProperty("data", image.data());
                item.add("source", source);
            } else if (block instanceof ContentBlock.Thinking thinking && assistant && same) {
                boolean redacted = thinking.thinking().equals("[Reasoning redacted]")
                        && thinking.signature() != null && !thinking.signature().isBlank();
                item.addProperty("type", redacted ? "redacted_thinking"
                        : thinking.signature() == null || thinking.signature().isBlank() ? "text" : "thinking");
                if (redacted) {
                    item.addProperty("data", thinking.signature());
                } else if (thinking.signature() == null || thinking.signature().isBlank()) {
                    item.addProperty("text", thinking.thinking());
                } else {
                    item.addProperty("thinking", thinking.thinking());
                    item.addProperty("signature", thinking.signature());
                }
            } else if (block instanceof ContentBlock.ToolCall call && assistant) {
                item.addProperty("type", "tool_use");
                item.addProperty("id", call.id());
                item.addProperty("name", call.name());
                item.add("input", call.arguments());
            } else {
                continue;
            }
            result.add(item);
        }
        return result;
    }

    private static void read(HttpResponse<InputStream> response, CancellationToken cancellation, State state)
            throws IOException {
        try (var input = response.body(); var subscription = cancellation.onCancel(() -> close(input))) {
            cancellation.throwIfCancelled();
            var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            var data = new StringBuilder();
            String line;
            while (!state.terminal && (line = reader.readLine()) != null) {
                cancellation.throwIfCancelled();
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        state.event(data.toString());
                        data.setLength(0);
                    }
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.startsWith("data: ") ? line.substring(6) : line.substring(5));
                }
            }
            if (!data.isEmpty()) {
                throw new IOException("Incomplete SSE frame");
            }
            cancellation.throwIfCancelled();
            if (!state.terminal) {
                throw new IOException("Anthropic stream ended before message_stop");
            }
        }
    }

    private static void close(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }

    private static String string(JsonObject object, String key) {
        var value = object == null ? null : object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static JsonObject object(JsonObject object, String key) {
        var value = object == null ? null : object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static final class State {

        private final Model model;
        private final AssistantMessageEventStream stream;
        private final List<ContentBlock> content = new ArrayList<>();
        private final Map<Integer, Slot> slots = new HashMap<>();
        private final long timestamp = System.currentTimeMillis();
        private long input;
        private long output;
        private long cacheRead;
        private long cacheWrite;
        private StopReason reason = StopReason.STOP;
        private String error;
        private boolean terminal;

        private State(Model model, AssistantMessageEventStream stream) {
            this.model = model;
            this.stream = stream;
        }

        private AssistantMessage snapshot() {
            var price = model.cost();
            double a = price == null ? 0 : input * price.input() / 1_000_000;
            double b = price == null ? 0 : output * price.output() / 1_000_000;
            double c = price == null ? 0 : cacheRead * price.cacheRead() / 1_000_000;
            double d = price == null ? 0 : cacheWrite * price.cacheWrite() / 1_000_000;
            var usage = new Usage(input, output, cacheRead, cacheWrite, 0, input + output + cacheRead + cacheWrite,
                    new Usage.Cost(a, b, c, d, a + b + c + d));
            return new AssistantMessage(content, model.api(), model.provider(), model.id(), usage, reason, error,
                    timestamp);
        }

        private void fail(StopReason value, String message) {
            reason = value;
            error = message;
        }

        private void event(String data) {
            var event = StrictJson.parse(data).getAsJsonObject();
            switch (string(event, "type")) {
                case "message_start" -> {
                    usage(object(object(event, "message"), "usage"));
                    stream.publish(new AssistantMessageEvent.Start(snapshot()));
                }
                case "content_block_start" -> start(event);
                case "content_block_delta" -> delta(event);
                case "content_block_stop" -> end(event);
                case "message_delta" -> {
                    usage(object(event, "usage"));
                    var delta = object(event, "delta");
                    String stop = string(delta, "stop_reason");
                    if (!stop.isEmpty()) {
                        switch (stop) {
                            case "end_turn", "stop_sequence", "pause_turn" -> reason = StopReason.STOP;
                            case "max_tokens" -> reason = StopReason.LENGTH;
                            case "tool_use" -> reason = StopReason.TOOL_USE;
                            case "refusal", "sensitive" -> fail(StopReason.ERROR, "Provider stopped with: " + stop);
                            default -> throw new IllegalStateException("Unhandled stop reason: " + stop);
                        }
                    }
                }
                case "message_stop" -> terminal = true;
                case "error" -> throw new IllegalStateException("Anthropic stream error: "
                        + string(object(event, "error"), "message"));
                default -> {
                }
            }
        }

        private void usage(JsonObject value) {
            if (value == null) {
                return;
            }
            if (value.has("input_tokens")) {
                input = value.get("input_tokens").getAsLong();
            }
            if (value.has("output_tokens")) {
                output = value.get("output_tokens").getAsLong();
            }
            if (value.has("cache_read_input_tokens") && !value.get("cache_read_input_tokens").isJsonNull()) {
                cacheRead = value.get("cache_read_input_tokens").getAsLong();
            }
            if (value.has("cache_creation_input_tokens") && !value.get("cache_creation_input_tokens").isJsonNull()) {
                cacheWrite = value.get("cache_creation_input_tokens").getAsLong();
            }
        }

        private void start(JsonObject event) {
            int index = event.get("index").getAsInt();
            var item = object(event, "content_block");
            var kind = switch (string(item, "type")) {
                case "text" -> AssistantMessageEvent.Kind.TEXT;
                case "thinking", "redacted_thinking" -> AssistantMessageEvent.Kind.THINKING;
                case "tool_use" -> AssistantMessageEvent.Kind.TOOLCALL;
                default -> null;
            };
            if (kind == null) {
                return;
            }
            var slot = new Slot(content.size(), kind);
            if (slots.putIfAbsent(index, slot) != null) {
                throw new IllegalStateException("Duplicate Anthropic content block");
            }
            switch (kind) {
                case TEXT -> content.add(new ContentBlock.Text(string(item, "text")));
                case THINKING -> content.add(string(item, "type").equals("redacted_thinking")
                        ? new ContentBlock.Thinking("[Reasoning redacted]", string(item, "data"))
                        : new ContentBlock.Thinking(string(item, "thinking"), string(item, "signature")));
                case TOOLCALL -> content.add(new ContentBlock.ToolCall(string(item, "id"), string(item, "name"),
                        object(item, "input") == null ? new JsonObject() : object(item, "input")));
            }
            stream.publish(new AssistantMessageEvent.BlockStart(kind, slot.index, snapshot()));
        }

        private void delta(JsonObject event) {
            var slot = slots.get(event.get("index").getAsInt());
            if (slot == null || slot.ended) {
                throw new IllegalStateException("Anthropic delta has no open content block");
            }
            var delta = object(event, "delta");
            String type = string(delta, "type");
            String value;
            if (type.equals("text_delta") && slot.kind == AssistantMessageEvent.Kind.TEXT) {
                value = string(delta, "text");
                var old = (ContentBlock.Text) content.get(slot.index);
                content.set(slot.index, new ContentBlock.Text(old.text() + value));
            } else if (type.equals("thinking_delta") && slot.kind == AssistantMessageEvent.Kind.THINKING) {
                value = string(delta, "thinking");
                var old = (ContentBlock.Thinking) content.get(slot.index);
                content.set(slot.index, new ContentBlock.Thinking(old.thinking() + value, old.signature()));
            } else if (type.equals("signature_delta") && slot.kind == AssistantMessageEvent.Kind.THINKING) {
                var old = (ContentBlock.Thinking) content.get(slot.index);
                content.set(slot.index, new ContentBlock.Thinking(old.thinking(),
                        old.signature() + string(delta, "signature")));
                return;
            } else if (type.equals("input_json_delta") && slot.kind == AssistantMessageEvent.Kind.TOOLCALL) {
                value = string(delta, "partial_json");
                slot.hasArguments = true;
                var old = (ContentBlock.ToolCall) content.get(slot.index);
                content.set(slot.index, new ContentBlock.ToolCall(old.id(), old.name(), slot.parser.append(value)));
            } else {
                return;
            }
            stream.publish(new AssistantMessageEvent.Delta(slot.kind, slot.index, value, snapshot()));
        }

        private void end(JsonObject event) {
            var slot = slots.get(event.get("index").getAsInt());
            if (slot == null || slot.ended) {
                return;
            }
            if (slot.kind == AssistantMessageEvent.Kind.TOOLCALL && slot.hasArguments) {
                var old = (ContentBlock.ToolCall) content.get(slot.index);
                var parsed = slot.parser.finish();
                content.set(slot.index, new ContentBlock.ToolCall(old.id(), old.name(), parsed.arguments(),
                        parsed.error()));
            }
            slot.ended = true;
            stream.publish(new AssistantMessageEvent.BlockEnd(slot.kind, slot.index, snapshot()));
        }
    }

    private static final class Slot {

        private final int index;
        private final AssistantMessageEvent.Kind kind;
        private final StreamingJson parser = new StreamingJson();
        private boolean hasArguments;
        private boolean ended;

        private Slot(int index, AssistantMessageEvent.Kind kind) {
            this.index = index;
            this.kind = kind;
        }
    }
}
