package io.github.izakyl.jpi.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.izakyl.jpi.ai.internal.StrictJson;
import io.github.izakyl.jpi.auth.Credential;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Google Generative AI backend over SSE. */
public final class GoogleGenerativeAi implements AiProvider {

    public static final String API = "google-generative-ai";
    public static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

    private final HttpClient client;

    public GoogleGenerativeAi(HttpClient client) {
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
        String key = null;
        try {
            options.cancellation().throwIfCancelled();
            key = options.apiKey();
            if ((key == null || key.isBlank()) && options.apiKeyResolver() != null) {
                key = options.apiKeyResolver().resolve(model);
            }
            if ((key == null || key.isBlank()) && options.credentialResolver() != null) {
                var credential = options.credentialResolver().resolve(model, options.cancellation());
                if (credential != null) {
                    key = credential.apiKey();
                }
            }
            if (key == null || key.isBlank()) {
                throw new IOException("API key required");
            }
            var headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            headers.put("Content-Type", "application/json");
            headers.put("Accept", "text/event-stream");
            headers.put("x-goog-api-key", key);
            headers.putAll(model.headers());
            headers.putAll(options.headers());
            String base = model.baseUrl().replaceAll("/+$", "");
            String id = URLEncoder.encode(model.id(), StandardCharsets.UTF_8).replace("+", "%20");
            var builder = HttpRequest.newBuilder(URI.create(base + "/models/" + id
                    + ":streamGenerateContent?alt=sse"))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            UnicodeSanitizer.sanitize(requestBody(model, context, options).toString())));
            headers.forEach(builder::setHeader);
            options.cancellation().throwIfCancelled();
            read(ProviderTransport.send(client, builder.build(), options, budget), options.cancellation(), state);
        } catch (Exception error) {
            Throwable cause = error;
            while (!(cause instanceof IOException) && cause.getCause() != null) {
                cause = cause.getCause();
            }
            String message = cause.getMessage() == null ? "Google request failed" : cause.getMessage();
            if (key != null && !key.isEmpty()) {
                message = message.replace(key, "[redacted]");
            }
            state.reason = options.cancellation().isCancelled() ? StopReason.ABORTED : StopReason.ERROR;
            state.error = options.cancellation().isCancelled() ? "Request was aborted" : message;
        }
        stream.complete(state.snapshot());
    }

    private void read(HttpResponse<InputStream> response, CancellationToken cancellation, State state)
            throws IOException {
        try (var input = response.body(); var subscription = cancellation.onCancel(() -> close(input))) {
            cancellation.throwIfCancelled();
            state.stream.publish(new AssistantMessageEvent.Start(state.snapshot()));
            var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            var data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
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
            state.finish();
        }
    }

    private static void close(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }

    JsonObject requestBody(Model model, Context context, StreamOptions options) {
        var body = new JsonObject();
        var contents = new JsonArray();
        var toolResponses = new JsonArray();
        var fallbackImages = new JsonArray();
        for (var message : context.messages()) {
            if (message instanceof ToolResultMessage result) {
                addToolResult(toolResponses, fallbackImages, result, model);
                continue;
            }
            var parts = new JsonArray();
            boolean same = message instanceof AssistantMessage assistant
                    && assistant.provider().equals(model.provider()) && assistant.model().equals(model.id());
            for (var block : message.content()) {
                var part = new JsonObject();
                if (block instanceof ContentBlock.Text text) {
                    part.addProperty("text", text.text());
                    signature(part, same, text.signature());
                } else if (block instanceof ContentBlock.Thinking thinking) {
                    part.addProperty("text", thinking.thinking());
                    if (same) {
                        part.addProperty("thought", true);
                        signature(part, true, thinking.signature());
                    }
                } else if (block instanceof ContentBlock.Image image) {
                    part = image(image);
                } else if (block instanceof ContentBlock.ToolCall call) {
                    var function = new JsonObject();
                    function.addProperty("name", call.name());
                    function.add("args", call.arguments());
                    if (requiresId(model)) {
                        function.addProperty("id", normalizeId(call.id()));
                    }
                    part.add("functionCall", function);
                    signature(part, same, call.signature());
                }
                parts.add(part);
            }
            if (!parts.isEmpty()) {
                flushToolResults(contents, toolResponses, fallbackImages);
                toolResponses = new JsonArray();
                fallbackImages = new JsonArray();
                contents.add(content(message instanceof AssistantMessage ? "model" : "user", parts));
            }
        }
        flushToolResults(contents, toolResponses, fallbackImages);
        body.add("contents", contents);
        if (context.systemPrompt() != null && !context.systemPrompt().isBlank()) {
            var parts = new JsonArray();
            var text = new JsonObject();
            text.addProperty("text", context.systemPrompt());
            parts.add(text);
            body.add("systemInstruction", content("user", parts));
        }
        if (!context.tools().isEmpty()) {
            var declarations = new JsonArray();
            for (var tool : context.tools()) {
                var declaration = new JsonObject();
                declaration.addProperty("name", tool.name());
                declaration.addProperty("description", tool.description());
                declaration.add("parametersJsonSchema", tool.parameters());
                declarations.add(declaration);
            }
            var group = new JsonObject();
            group.add("functionDeclarations", declarations);
            var tools = new JsonArray();
            tools.add(group);
            body.add("tools", tools);
            if (options.toolChoice() != null) {
                var config = new JsonObject();
                String choice = options.toolChoice();
                config.addProperty("mode", choice.equals("none") ? "NONE"
                        : choice.equals("auto") ? "AUTO" : "ANY");
                if (!List.of("none", "auto", "required", "any").contains(choice)) {
                    var names = new JsonArray();
                    names.add(choice);
                    config.add("allowedFunctionNames", names);
                }
                var toolConfig = new JsonObject();
                toolConfig.add("functionCallingConfig", config);
                body.add("toolConfig", toolConfig);
            }
        }
        var generation = new JsonObject();
        if (options.temperature() != null) {
            generation.addProperty("temperature", options.temperature());
        }
        if (options.maxTokens() != null) {
            generation.addProperty("maxOutputTokens", options.maxTokens());
        }
        if (model.reasoning()) {
            var thinking = new JsonObject();
            String effort = options.reasoningEffort();
            if (effort == null || effort.equals("off") || effort.equals("none")) {
                if (model.id().matches("(?i).*gemini-3(?:\\.\\d+)?-pro.*")) {
                    thinking.addProperty("thinkingLevel", "LOW");
                } else {
                    thinking.addProperty("thinkingBudget", 0);
                }
            } else {
                thinking.addProperty("includeThoughts", true);
                if (model.id().matches("(?i).*gemini-(3(?:\\.\\d+)?|[4-9]).*")) {
                    thinking.addProperty("thinkingLevel", effort.toUpperCase());
                } else {
                    thinking.addProperty("thinkingBudget", switch (effort) {
                        case "minimal" -> 128;
                        case "low" -> 2048;
                        case "medium" -> 8192;
                        case "high" -> model.id().contains("2.5-pro") ? 32768 : 24576;
                        default -> -1;
                    });
                }
            }
            generation.add("thinkingConfig", thinking);
        }
        if (!generation.isEmpty()) {
            body.add("generationConfig", generation);
        }
        return body;
    }

    private static void addToolResult(JsonArray responses, JsonArray fallback,
            ToolResultMessage result, Model model) {
        var images = new JsonArray();
        var texts = new ArrayList<String>();
        for (var block : result.content()) {
            if (block instanceof ContentBlock.Text text) {
                texts.add(text.text());
            } else if (block instanceof ContentBlock.Image image && model.input().contains("image")) {
                images.add(image(image));
            }
        }
        String text = String.join("\n", texts);
        if (text.isBlank() && !images.isEmpty()) {
            text = "(see attached image)";
        }
        var response = new JsonObject();
        response.addProperty(result.isError() ? "error" : "output", text);
        var function = new JsonObject();
        function.addProperty("name", result.toolName());
        function.add("response", response);
        if (requiresId(model)) {
            function.addProperty("id", normalizeId(result.toolCallId()));
        }
        boolean multimodal = !model.id().matches("(?i)^gemini-(1|2)(\\D.*|$)");
        if (multimodal && !images.isEmpty()) {
            function.add("parts", images);
        }
        var part = new JsonObject();
        part.add("functionResponse", function);
        responses.add(part);
        if (!multimodal) {
            fallback.addAll(images);
        }
    }

    private static void flushToolResults(JsonArray contents, JsonArray responses, JsonArray images) {
        if (!responses.isEmpty()) {
            contents.add(content("user", responses));
        }
        if (!images.isEmpty()) {
            var parts = new JsonArray();
            var label = new JsonObject();
            label.addProperty("text", "Tool result image:");
            parts.add(label);
            parts.addAll(images);
            contents.add(content("user", parts));
        }
    }

    private static JsonObject content(String role, JsonArray parts) {
        var content = new JsonObject();
        content.addProperty("role", role);
        content.add("parts", parts);
        return content;
    }

    private static JsonObject image(ContentBlock.Image image) {
        var data = new JsonObject();
        data.addProperty("mimeType", image.mimeType());
        data.addProperty("data", image.data());
        var part = new JsonObject();
        part.add("inlineData", data);
        return part;
    }

    private static void signature(JsonObject part, boolean same, String signature) {
        if (same && signature != null && signature.matches("[A-Za-z0-9+/]+={0,2}")
                && signature.length() % 4 == 0) {
            part.addProperty("thoughtSignature", signature);
        }
    }

    private static boolean requiresId(Model model) {
        return model.id().matches("(?i)^(gemini(-live)?-([3-9]|[1-9][0-9]+).*|claude-.*|gpt-oss-.*)");
    }

    private static String normalizeId(String id) {
        String normalized = id.replaceAll("[^a-zA-Z0-9_-]", "_");
        return normalized.substring(0, Math.min(64, normalized.length()));
    }

    private static String string(JsonObject object, String key) {
        var value = object == null ? null : object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static long number(JsonObject object, String key) {
        return object != null && object.has(key) ? object.get(key).getAsLong() : 0;
    }

    private static JsonObject object(JsonObject object, String key) {
        var value = object == null ? null : object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static final class State {
        private final Model model;
        private final AssistantMessageEventStream stream;
        private final List<ContentBlock> content = new ArrayList<>();
        private final long timestamp = System.currentTimeMillis();
        private Usage usage = Usage.ZERO;
        private StopReason reason = StopReason.STOP;
        private String error;
        private boolean terminal;
        private AssistantMessageEvent.Kind current;
        private StreamingJson parser;
        private StringBuilder raw;

        private State(Model model, AssistantMessageEventStream stream) {
            this.model = model;
            this.stream = stream;
        }

        private AssistantMessage snapshot() {
            return new AssistantMessage(content, model.api(), model.provider(), model.id(), usage, reason, error,
                    timestamp);
        }

        private void event(String data) {
            if (data.trim().equals("[DONE]")) {
                return;
            }
            var chunk = StrictJson.parse(data).getAsJsonObject();
            var failure = object(chunk, "error");
            if (failure != null) {
                throw new IllegalStateException("Google stream error: " + string(failure, "message"));
            }
            var candidates = chunk.getAsJsonArray("candidates");
            if (candidates != null && !candidates.isEmpty()) {
                var candidate = candidates.get(0).getAsJsonObject();
                var body = object(candidate, "content");
                var parts = body == null ? null : body.getAsJsonArray("parts");
                if (parts != null) {
                    for (JsonElement element : parts) {
                        part(element.getAsJsonObject());
                    }
                }
                String finish = string(candidate, "finishReason");
                if (!finish.isEmpty()) {
                    terminal = true;
                    reason = switch (finish) {
                        case "STOP" -> content.stream().anyMatch(ContentBlock.ToolCall.class::isInstance)
                                ? StopReason.TOOL_USE : StopReason.STOP;
                        case "MAX_TOKENS" -> StopReason.LENGTH;
                        default -> StopReason.ERROR;
                    };
                    if (reason == StopReason.ERROR) {
                        error = "Provider stopped with: " + finish;
                    }
                }
            }
            var metadata = object(chunk, "usageMetadata");
            if (metadata != null) {
                long cached = number(metadata, "cachedContentTokenCount");
                long input = number(metadata, "promptTokenCount") - cached;
                long thinking = number(metadata, "thoughtsTokenCount");
                long output = number(metadata, "candidatesTokenCount") + thinking;
                var cost = Usage.Cost.ZERO;
                if (model.cost() != null) {
                    double in = input * model.cost().input() / 1_000_000;
                    double out = output * model.cost().output() / 1_000_000;
                    double read = cached * model.cost().cacheRead() / 1_000_000;
                    cost = new Usage.Cost(in, out, read, 0, in + out + read);
                }
                usage = new Usage(input, output, cached, 0, thinking, number(metadata, "totalTokenCount"), cost);
            }
        }

        private void part(JsonObject part) {
            if (part.has("text")) {
                boolean thought = part.has("thought") && part.get("thought").getAsBoolean();
                var kind = thought ? AssistantMessageEvent.Kind.THINKING : AssistantMessageEvent.Kind.TEXT;
                if (current != kind) {
                    end();
                    current = kind;
                    content.add(thought ? new ContentBlock.Thinking("") : new ContentBlock.Text(""));
                    stream.publish(new AssistantMessageEvent.BlockStart(kind, content.size() - 1, snapshot()));
                }
                int index = content.size() - 1;
                String delta = string(part, "text");
                String sig = string(part, "thoughtSignature");
                if (thought) {
                    var old = (ContentBlock.Thinking) content.get(index);
                    content.set(index, new ContentBlock.Thinking(old.thinking() + delta,
                            sig.isEmpty() ? old.signature() : sig));
                } else {
                    var old = (ContentBlock.Text) content.get(index);
                    content.set(index, new ContentBlock.Text(old.text() + delta,
                            sig.isEmpty() ? old.signature() : sig));
                }
                stream.publish(new AssistantMessageEvent.Delta(kind, index, delta, snapshot()));
            }
            var function = object(part, "functionCall");
            if (function != null) {
                String id = string(function, "id");
                String name = string(function, "name");
                boolean continuation = current == AssistantMessageEvent.Kind.TOOLCALL && !id.isEmpty()
                        && function.has("args") && function.get("args").isJsonPrimitive()
                        && ((ContentBlock.ToolCall) content.get(content.size() - 1)).id().equals(id);
                if (!continuation) {
                    end();
                    current = AssistantMessageEvent.Kind.TOOLCALL;
                    if (id.isEmpty()) {
                        id = name + "_" + UUID.randomUUID();
                    }
                    content.add(new ContentBlock.ToolCall(id, name, new JsonObject(), null,
                            string(part, "thoughtSignature")));
                    parser = new StreamingJson();
                    raw = new StringBuilder();
                    stream.publish(new AssistantMessageEvent.BlockStart(current, content.size() - 1, snapshot()));
                }
                int index = content.size() - 1;
                var old = (ContentBlock.ToolCall) content.get(index);
                var args = function.get("args");
                String delta = args == null ? "" : args.isJsonPrimitive() ? args.getAsString() : args.toString();
                if (!delta.isEmpty()) {
                    raw.append(delta);
                    var parsed = parser.append(delta);
                    String sig = string(part, "thoughtSignature");
                    content.set(index, new ContentBlock.ToolCall(old.id(), old.name(), parsed, null,
                            sig.isEmpty() ? old.signature() : sig));
                    stream.publish(new AssistantMessageEvent.Delta(current, index, delta, snapshot()));
                }
            }
        }

        private void end() {
            if (current == null) {
                return;
            }
            int index = content.size() - 1;
            if (current == AssistantMessageEvent.Kind.TOOLCALL) {
                var old = (ContentBlock.ToolCall) content.get(index);
                if (raw.isEmpty()) {
                    parser.append("{}");
                }
                var args = parser.finish();
                content.set(index, new ContentBlock.ToolCall(old.id(), old.name(), args.arguments(), args.error(),
                        old.signature()));
            }
            stream.publish(new AssistantMessageEvent.BlockEnd(current, index, snapshot()));
            current = null;
        }

        private void finish() {
            end();
            if (!terminal) {
                throw new IllegalStateException("Google stream ended without a finish reason");
            }
        }
    }
}
