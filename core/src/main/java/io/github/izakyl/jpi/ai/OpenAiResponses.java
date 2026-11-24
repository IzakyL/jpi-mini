package io.github.izakyl.jpi.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
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
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/** OpenAI Responses backend over SSE, ported from pi. */
public final class OpenAiResponses implements AiProvider {

    public static final String API = "openai-responses";

    public static final String BASE_URL = "https://api.openai.com/v1";

    private final HttpClient client;

    public OpenAiResponses(HttpClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
        var actual = options == null ? StreamOptions.defaults() : options;
        return ProviderStreams.start(model, actual, (stream, budget) -> run(model, context, actual, stream, budget));
    }

    private void run(Model model, Context context, StreamOptions options, AssistantMessageEventStream stream,
            RetryBudget budget) {
        var state = new OpenAiResponsesStream(model, stream);
        Credential credential = null;
        try {
            options.cancellation().throwIfCancelled();
            credential = credential(model, options);
            var request = request(model, context, options, credential);
            options.cancellation().throwIfCancelled();
            readResponse(ProviderTransport.send(client, request, options, budget), options.cancellation(), state);
        } catch (Exception error) {
            state.fail(options.cancellation().isCancelled() ? StopReason.ABORTED : StopReason.ERROR,
                    options.cancellation().isCancelled() ? "Request was aborted" : safeError(error, credential));
        }
        stream.complete(state.snapshot());
    }

    private Credential credential(Model model, StreamOptions options) throws Exception {
        if (options.apiKey() != null && !options.apiKey().isBlank()) {
            return new Credential.ApiKey(options.apiKey());
        }
        if (options.apiKeyResolver() != null) {
            String token = options.apiKeyResolver().resolve(model);
            if (token != null && !token.isBlank()) {
                return new Credential.ApiKey(token);
            }
        }
        if (options.credentialResolver() != null) {
            var credential = options.credentialResolver().resolve(model, options.cancellation());
            if (credential != null) {
                return credential;
            }
        }
        throw new IOException("API key required");
    }

    private HttpRequest request(Model model, Context context, StreamOptions options, Credential credential)
            throws IOException {
        String token = credential.apiKey();
        var headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        headers.put("Accept", "text/event-stream");
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + token);
        headers.putAll(model.headers());
        headers.putAll(options.headers());
        String base = model.baseUrl().replaceAll("/+$", "");
        String endpoint = base.endsWith("/responses") ? base : base + "/responses";
        var request = HttpRequest.newBuilder(URI.create(endpoint))
                .POST(HttpRequest.BodyPublishers.ofString(
                            UnicodeSanitizer.sanitize(requestBody(model, context, options).toString()),
                        StandardCharsets.UTF_8));
        headers.forEach(request::setHeader);
        return request.build();
    }

    JsonObject requestBody(Model model, Context context, StreamOptions options) {
        var body = new JsonObject();
        body.addProperty("model", model.id());
        body.addProperty("store", false);
        body.addProperty("stream", true);
        if (context.systemPrompt() != null && !context.systemPrompt().isBlank()) {
            body.addProperty("instructions", context.systemPrompt());
        }
        body.add("input", OpenAiResponsesMessages.convert(model, context.messages()));
        var include = new JsonArray();
        include.add("reasoning.encrypted_content");
        body.add("include", include);
        body.addProperty("parallel_tool_calls", true);
        addToolChoice(body, options.toolChoice());
        if (!context.tools().isEmpty()) {
            var tools = new JsonArray();
            for (var tool : context.tools()) {
                var function = new JsonObject();
                function.addProperty("type", "function");
                function.addProperty("name", tool.name());
                function.addProperty("description", tool.description());
                function.add("parameters", tool.parameters());
                function.add("strict", JsonNull.INSTANCE);
                tools.add(function);
            }
            body.add("tools", tools);
        }
        if (options.reasoningEffort() != null || model.reasoning()) {
            var reasoning = new JsonObject();
            reasoning.addProperty("effort", options.reasoningEffort() == null ? "none" : options.reasoningEffort());
            reasoning.addProperty("summary", "auto");
            body.add("reasoning", reasoning);
        }
        body.addProperty("max_output_tokens", options.maxTokens() == null ? model.maxTokens() : options.maxTokens());
        if (options.temperature() != null) {
            body.addProperty("temperature", options.temperature());
        }
        return body;
    }

    private static void addToolChoice(JsonObject body, String choice) {
        if (choice == null || List.of("auto", "none", "required").contains(choice)) {
            body.addProperty("tool_choice", choice == null ? "auto" : choice);
        } else {
            var function = new JsonObject();
            function.addProperty("type", "function");
            function.addProperty("name", choice);
            body.add("tool_choice", function);
        }
    }

    private void readResponse(HttpResponse<InputStream> response, CancellationToken cancellation,
            OpenAiResponsesStream state) throws IOException {
        try (var input = response.body();
                var subscription = cancellation.onCancel(() -> close(input))) {
            cancellation.throwIfCancelled();
            state.start();
            var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            var data = new StringBuilder();
            String line;
            boolean ended = false;
            while (!ended && (line = reader.readLine()) != null) {
                cancellation.throwIfCancelled();
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        ended = state.event(data.toString());
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

    private static String safeError(Exception error, Credential credential) {
        Throwable cause = error;
        while (!(cause instanceof IOException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() == null ? "Responses request failed" : cause.getMessage();
        if (credential != null) {
            message = redact(message, credential.apiKey());
        }
        return message;
    }

    private static String redact(String message, String secret) {
        return secret == null || secret.isEmpty() ? message : message.replace(secret, "[redacted]");
    }
}
