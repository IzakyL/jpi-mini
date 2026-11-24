package io.github.izakyl.jpi.ai;

import static io.github.izakyl.jpi.ai.AssistantMessageEvent.Kind.TEXT;
import static io.github.izakyl.jpi.ai.AssistantMessageEvent.Kind.THINKING;
import static io.github.izakyl.jpi.ai.AssistantMessageEvent.Kind.TOOLCALL;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * OpenAI-compatible Chat Completions over SSE; all request/stream failures become terminal
 * messages.
 */
public final class OpenAiCompletions implements AiProvider {

    private final HttpClient client;

    public OpenAiCompletions(HttpClient client) {
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
        var cancellation = options.cancellation();
        try {
            cancellation.throwIfCancelled();
            var request = request(model, context, options);
            cancellation.throwIfCancelled();
            exchange(request, options, state, budget);
        } catch (Exception error) {
            state.reason = cancellation.isCancelled() ? StopReason.ABORTED : StopReason.ERROR;
            var cause = error;
            while (!(cause instanceof IOException) && cause.getCause() instanceof Exception nested) {
                cause = nested;
            }
            state.error = cancellation.isCancelled() ? "Request was aborted"
                    : Objects.toString(cause.getMessage(), "Chat Completions request failed");
        }
        stream.complete(state.snapshot());
    }

    private HttpRequest.Builder request(Model model, Context context, StreamOptions options) throws Exception {
        var body = requestBody(model, context, options);
        var headers = requestHeaders(model, options);
        var endpoint = URI.create(model.baseUrl().replaceAll("/+$", "") + "/chat/completions");
        var request =
                HttpRequest.newBuilder(endpoint)
                        .POST(HttpRequest.BodyPublishers.ofString(
                            UnicodeSanitizer.sanitize(body.toString()), StandardCharsets.UTF_8));
        headers.forEach(request::setHeader);
        return request;
    }

    private Map<String, String> requestHeaders(Model model, StreamOptions options) throws Exception {
        var headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "text/event-stream");
        String key = options.apiKey();
        if ((key == null || key.isBlank()) && options.apiKeyResolver() != null) {
            key = options.apiKeyResolver().resolve(model);
        }
        if ((key == null || key.isBlank()) && options.credentialResolver() != null) {
            var credential = options.credentialResolver().resolve(model, options.cancellation());
            if (credential != null) {
                key = credential.apiKey();
            }
        }
        if (key != null && !key.isBlank()) {
            headers.put("Authorization", "Bearer " + key);
        }
        headers.putAll(model.headers());
        headers.putAll(options.headers());
        return headers;
    }

    private void exchange(HttpRequest.Builder request, StreamOptions options, State state, RetryBudget budget)
            throws IOException {
        readResponse(ProviderTransport.send(client, request.build(), options, budget), options.cancellation(), state);
    }

    private void readResponse(HttpResponse<InputStream> response, CancellationToken cancellation,
            State state) throws IOException {
        try (var input = response.body();
                var bodyCancellation = cancellation.onCancel(() -> closeResponse(input))) {
            cancellation.throwIfCancelled();
            state.stream.publish(new AssistantMessageEvent.Start(state.snapshot()));
            var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            boolean readDone = readEvents(reader, cancellation, state);
            cancellation.throwIfCancelled();
            state.finish(readDone);
        }
    }

    private static void closeResponse(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }

    private boolean readEvents(BufferedReader reader, CancellationToken cancellation, State state) throws IOException {
        var data = new StringBuilder();
        boolean ended = false;
        String line;
        while (!ended && (line = reader.readLine()) != null) {
            cancellation.throwIfCancelled();
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    ended = state.chunk(data.toString());
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
        return ended;
    }

    JsonObject requestBody(Model model, Context context, StreamOptions options) {
        var body = requestSettings(model, options);
        body.add("messages", requestMessages(model, context));
        boolean toolHistory =
                context.messages().stream()
                        .anyMatch(
                                message ->
                                        message instanceof ToolResultMessage
                                                || message instanceof AssistantMessage assistant
                                                        && !assistant.toolCalls().isEmpty());
        if (!context.tools().isEmpty() || toolHistory) {
            body.add("tools", requestTools(context.tools()));
        }
        return body;
    }

    private JsonObject requestSettings(Model model, StreamOptions options) {
        var compat = model.compat();
        var body = new JsonObject();
        body.addProperty("model", model.id());
        body.addProperty("stream", true);
        body.addProperty(
                compat.maxTokensField(), options.maxTokens() == null ? model.maxTokens() : options.maxTokens());
        if (options.temperature() != null) {
            body.addProperty("temperature", options.temperature());
        }
        if (compat.supportsStore()) {
            body.addProperty("store", false);
        }
        if (compat.supportsUsageInStreaming()) {
            var streamOptions = new JsonObject();
            streamOptions.addProperty("include_usage", true);
            body.add("stream_options", streamOptions);
        }
        if (model.reasoning() && compat.supportsReasoningEffort() && options.reasoningEffort() != null) {
            body.addProperty("reasoning_effort", options.reasoningEffort());
        }
        if (options.toolChoice() != null
                && (!options.toolChoice().equals("auto") || compat.supportsToolChoiceAuto())) {
            if (List.of("auto", "none", "required").contains(options.toolChoice())) {
                body.addProperty("tool_choice", options.toolChoice());
            } else {
                var function = new JsonObject();
                function.addProperty("name", options.toolChoice());
                var choice = new JsonObject();
                choice.addProperty("type", "function");
                choice.add("function", function);
                body.add("tool_choice", choice);
            }
        }
        compat.extraBody().entrySet().forEach(entry -> body.add(entry.getKey(), entry.getValue()));
        return body;
    }

    private JsonArray requestMessages(Model model, Context context) {
        var compat = model.compat();
        var messages = new JsonArray();
        if (context.systemPrompt() != null && !context.systemPrompt().isEmpty()) {
            String role = model.reasoning() && compat.supportsDeveloperRole() ? "developer" : "system";
            messages.add(message(role, context.systemPrompt()));
        }
        Message previous = null;
        // Images from tool results wait until the run of tool messages ends: an assistant turn with several tool
        // calls must be answered by all its tool messages before anything else comes.
        var images = new ArrayList<ContentBlock>();
        for (var source : context.messages()) {
            if (!(source instanceof ToolResultMessage)) {
                flushImages(messages, images);
            }
            if (compat.requiresAssistantAfterToolResult()
                    && previous instanceof ToolResultMessage
                    && source instanceof UserMessage) {
                messages.add(message("assistant", "Done."));
            }
            if (source instanceof UserMessage user) {
                messages.add(withContent("user", inputContent(user.content())));
            } else if (source instanceof AssistantMessage assistant) {
                if (assistant.stopReason().isFailure()) {
                    continue;
                }
                messages.add(assistantMessage(assistant, compat));
            } else if (source instanceof ToolResultMessage result) {
                addToolResult(messages, result, compat, images);
            }
            previous = source;
        }
        flushImages(messages, images);
        return messages;
    }

    private void flushImages(JsonArray messages, List<ContentBlock> images) {
        if (images.isEmpty()) {
            return;
        }
        var content = new ArrayList<ContentBlock>();
        content.add(new ContentBlock.Text("Attached image(s) from tool result:"));
        content.addAll(images);
        messages.add(withContent("user", inputContent(content)));
        images.clear();
    }

    private JsonObject assistantMessage(AssistantMessage assistant, OpenAiCompat compat) {
        var target = message("assistant", assistant.text());
        if (assistant.text().isEmpty() && !compat.requiresAssistantAfterToolResult()) {
            target.add("content", JsonNull.INSTANCE);
        }
        var calls = new JsonArray();
        var thinkingText = new ArrayList<String>();
        for (var block : assistant.content()) {
            if (block instanceof ContentBlock.ToolCall call) {
                calls.add(toolCall(call));
            } else if (block instanceof ContentBlock.Thinking thinking) {
                addThinking(target, thinkingText, thinking, compat);
            }
        }
        if (!thinkingText.isEmpty()) {
            target.addProperty(
                    "content",
                    String.join("\n\n", thinkingText) + (assistant.text().isEmpty() ? "" : "\n\n" + assistant.text()));
        }
        if (!calls.isEmpty()) {
            target.add("tool_calls", calls);
            if (compat.requiresAssistantContentForToolCalls() && target.get("content").isJsonNull()) {
                target.addProperty("content", "");
            }
        }
        if ((compat.requiresReasoningContent() || compat.requiresReasoningContentForAllAssistantTurns()
                || compat.requiresReasoningContentForToolCalls() && !calls.isEmpty())
                && !target.has("reasoning_content")) {
            target.addProperty("reasoning_content", "");
        }
        return target;
    }

    private JsonObject toolCall(ContentBlock.ToolCall call) {
        var function = new JsonObject();
        function.addProperty("name", call.name());
        function.addProperty("arguments", call.arguments().toString());
        var item = new JsonObject();
        item.addProperty("id", call.id());
        item.addProperty("type", "function");
        item.add("function", function);
        return item;
    }

    private void addThinking(JsonObject target, List<String> thinkingText, ContentBlock.Thinking thinking,
            OpenAiCompat compat) {
        if (compat.requiresThinkingAsText()) {
            if (!thinking.thinking().isBlank()) {
                thinkingText.add(thinking.thinking());
            }
        } else {
            String field = thinking.signature();
            if (field == null || !compat.reasoningFields().contains(field)) {
                field = "reasoning_content";
            }
            target.addProperty(field, (target.has(field) ? target.get(field).getAsString() : "") + thinking.thinking());
        }
    }

    private void addToolResult(JsonArray messages, ToolResultMessage result, OpenAiCompat compat,
            List<ContentBlock> images) {
        var target = message("tool", text(result.content()));
        target.addProperty("tool_call_id", result.toolCallId());
        if (compat.requiresToolResultName()) {
            target.addProperty("name", result.toolName());
        }
        messages.add(target);
        result.content().stream().filter(ContentBlock.Image.class::isInstance).forEach(images::add);
    }

    private JsonArray requestTools(List<Tool> declarations) {
        var tools = new JsonArray();
        for (var tool : declarations) {
            var function = new JsonObject();
            function.addProperty("name", tool.name());
            function.addProperty("description", tool.description());
            function.add("parameters", tool.parameters());
            var item = new JsonObject();
            item.addProperty("type", "function");
            item.add("function", function);
            tools.add(item);
        }
        return tools;
    }

    private static String text(List<ContentBlock> content) {
        return content.stream()
                .filter(ContentBlock.Text.class::isInstance)
                .map(ContentBlock.Text.class::cast)
                .map(ContentBlock.Text::text)
                .collect(Collectors.joining("\n"));
    }

    private static JsonObject message(String role, String content) {
        var message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static JsonObject withContent(String role, JsonElement content) {
        var message = new JsonObject();
        message.addProperty("role", role);
        message.add("content", content);
        return message;
    }

    private static JsonElement inputContent(List<ContentBlock> blocks) {
        if (blocks.stream().allMatch(ContentBlock.Text.class::isInstance)) {
            return new JsonPrimitive(text(blocks));
        }
        var content = new JsonArray();
        for (var block : blocks) {
            var item = new JsonObject();
            if (block instanceof ContentBlock.Text text) {
                item.addProperty("type", "text");
                item.addProperty("text", text.text());
            } else if (block instanceof ContentBlock.Image image) {
                item.addProperty("type", "image_url");
                var url = new JsonObject();
                url.addProperty("url", "data:" + image.mimeType() + ";base64," + image.data());
                item.add("image_url", url);
            }
            content.add(item);
        }
        return content;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static long number(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsLong() : 0;
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
    }

    private static final class Call {

        final int contentIndex;
        final StreamingJson parser = new StreamingJson();
        String id = "", name = "";

        Call(int contentIndex) {
            this.contentIndex = contentIndex;
        }
    }

    private static final class State {

        final Model model;
        final AssistantMessageEventStream stream;
        final List<ContentBlock> content = new ArrayList<>();
        final Map<Integer, Call> calls = new TreeMap<>();
        final DeepSeekText textFilter = new DeepSeekText();
        final long timestamp = System.currentTimeMillis();
        int textIndex = -1, thinkingIndex = -1;
        String signature;
        Usage usage = Usage.ZERO;
        StopReason reason = StopReason.STOP;
        String error;
        boolean hasFinishReason;

        State(Model model, AssistantMessageEventStream stream) {
            this.model = model;
            this.stream = stream;
        }

        AssistantMessage snapshot() {
            return new AssistantMessage(
                    content, model.api(), model.provider(), model.id(), usage, reason, error, timestamp);
        }

        boolean chunk(String data) {
            if (data.trim().equals("[DONE]")) {
                return true;
            }
            var chunk = StrictJson.parse(data).getAsJsonObject();
            if (chunk.has("error")) {
                String message = ContextOverflow.matchesError(chunk.get("error").toString())
                        ? "Provider error: context_length_exceeded" : "Provider returned a stream error";
                throw new IllegalStateException(message);
            }
            if (object(chunk, "usage") != null) {
                usage = usage(chunk.getAsJsonObject("usage"));
            }
            if (!chunk.has("choices") || chunk.getAsJsonArray("choices").isEmpty()) {
                return false;
            }
            var choice = chunk.getAsJsonArray("choices").get(0).getAsJsonObject();
            if (choice.has("index") && choice.get("index").getAsInt() != 0) {
                return false;
            }
            if (object(chunk, "usage") == null && object(choice, "usage") != null) {
                usage = usage(choice.getAsJsonObject("usage"));
            }
            finishReason(string(choice, "finish_reason"));
            var delta = object(choice, "delta");
            if (delta != null) {
                appendDelta(delta);
            }
            return false;
        }

        private void finishReason(String finish) {
            if (!finish.isEmpty()) {
                hasFinishReason = true;
                reason =
                        switch (finish) {
                            case "stop", "end" -> StopReason.STOP;
                            case "length" -> StopReason.LENGTH;
                            case "tool_calls", "function_call" -> StopReason.TOOL_USE;
                            default -> StopReason.ERROR;
                        };
                if (reason == StopReason.ERROR) {
                    error = "Provider finish_reason: " + finish;
                }
            }
        }

        private void appendDelta(JsonObject delta) {
            for (var field : model.compat().reasoningFields()) {
                String value = string(delta, field);
                if (!value.isEmpty()) {
                    appendThinking(field, value);
                    break;
                }
            }
            String text = string(delta, "content");
            if (model.compat().stripDeepseekSpecialTokens()) {
                text = textFilter.append(text);
            }
            if (!text.isEmpty()) {
                appendText(text);
            }
            if (delta.has("tool_calls")) {
                for (var element : delta.getAsJsonArray("tool_calls")) {
                    appendToolCall(element.getAsJsonObject());
                }
            }
        }

        private void appendToolCall(JsonObject tool) {
            int index = tool.get("index").getAsInt();
            var function = object(tool, "function");
            var call = calls.get(index);
            if (call == null) {
                call = startToolCall(index, tool, function);
            }
            if (!string(tool, "id").isEmpty()) {
                call.id = string(tool, "id");
            }
            if (function != null && !string(function, "name").isEmpty()) {
                call.name = string(function, "name");
            }
            String fragment = function == null ? "" : string(function, "arguments");
            var partial = call.parser.append(fragment);
            content.set(call.contentIndex, new ContentBlock.ToolCall(call.id, call.name, partial));
            stream.publish(new AssistantMessageEvent.Delta(TOOLCALL, call.contentIndex, fragment, snapshot()));
        }

        private Call startToolCall(int index, JsonObject tool, JsonObject function) {
            var call = new Call(content.size());
            call.id = string(tool, "id");
            call.name = function == null ? "" : string(function, "name");
            calls.put(index, call);
            content.add(new ContentBlock.ToolCall(call.id, call.name, new JsonObject()));
            stream.publish(new AssistantMessageEvent.BlockStart(TOOLCALL, call.contentIndex, snapshot()));
            return call;
        }

        void appendText(String delta) {
            if (textIndex == -1) {
                textIndex = content.size();
                content.add(new ContentBlock.Text(""));
                stream.publish(new AssistantMessageEvent.BlockStart(TEXT, textIndex, snapshot()));
            }
            content.set(textIndex, new ContentBlock.Text(((ContentBlock.Text) content.get(textIndex)).text() + delta));
            stream.publish(new AssistantMessageEvent.Delta(TEXT, textIndex, delta, snapshot()));
        }

        void appendThinking(String field, String delta) {
            if (thinkingIndex == -1) {
                signature = field;
                thinkingIndex = content.size();
                content.add(new ContentBlock.Thinking("", signature));
                stream.publish(new AssistantMessageEvent.BlockStart(THINKING, thinkingIndex, snapshot()));
            }
            content.set(
                    thinkingIndex,
                    new ContentBlock.Thinking(
                            ((ContentBlock.Thinking) content.get(thinkingIndex)).thinking() + delta, signature));
            stream.publish(new AssistantMessageEvent.Delta(THINKING, thinkingIndex, delta, snapshot()));
        }

        void finish(boolean done) {
            if (model.compat().stripDeepseekSpecialTokens()) {
                String tail = textFilter.finish();
                if (!tail.isEmpty()) {
                    appendText(tail);
                }
            }
            for (var call : calls.values()) {
                var arguments = call.parser.finish();
                content.set(
                        call.contentIndex,
                        new ContentBlock.ToolCall(call.id, call.name, arguments.arguments(), arguments.error()));
            }
            for (int i = 0; i < content.size(); i++) {
                var kind =
                        content.get(i) instanceof ContentBlock.Text
                                ? TEXT
                                : content.get(i) instanceof ContentBlock.Thinking ? THINKING : TOOLCALL;
                stream.publish(new AssistantMessageEvent.BlockEnd(kind, i, snapshot()));
            }
            if (!hasFinishReason) {
                if (model.compat().supportsFinishReason() || !done) {
                    throw new IllegalStateException("Stream ended without finish_reason");
                }
                reason = calls.isEmpty() ? StopReason.STOP : StopReason.TOOL_USE;
            }
        }

        Usage usage(JsonObject raw) {
            long cached = number(object(raw, "prompt_tokens_details"), "cached_tokens");
            if (cached == 0) {
                cached = number(raw, "prompt_cache_hit_tokens");
            }
            long prompt = number(raw, "prompt_tokens");
            long input = Math.max(0, prompt - cached);
            long output = number(raw, "completion_tokens");
            long total = raw.has("total_tokens") ? number(raw, "total_tokens") : prompt + output;
            var cost = Usage.Cost.ZERO;
            if (model.cost() != null) {
                double inputCost = input * model.cost().input() / 1_000_000;
                double outputCost = output * model.cost().output() / 1_000_000;
                double cacheReadCost = cached * model.cost().cacheRead() / 1_000_000;
                cost = new Usage.Cost(inputCost, outputCost, cacheReadCost, 0, inputCost + outputCost + cacheReadCost);
            }
            return new Usage(
                    input,
                    output,
                    cached,
                    0,
                    number(object(raw, "completion_tokens_details"), "reasoning_tokens"),
                    total,
                    cost);
        }
    }
}
