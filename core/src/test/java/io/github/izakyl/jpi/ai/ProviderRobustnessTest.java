package io.github.izakyl.jpi.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.izakyl.jpi.FakeServer;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProviderRobustnessTest {

    private final Context context = new Context(null, List.of(new UserMessage("hello")));
    private final OpenAiCompletions provider = new OpenAiCompletions(HttpClient.newHttpClient());

    @Test
    void retriesRateLimitAndServerError() throws Exception {
        try (var server = new FakeServer()) {
            server.enqueue(exchange -> {
                exchange.getResponseHeaders().set("Retry-After", "0");
                FakeServer.send(exchange, 429, "rate limit");
            });
            server.status(503, "busy");
            server.fixture("text");
            long start = System.nanoTime();
            var result = provider.stream(server.model(), context,
                    StreamOptions.builder().maxRetries(2).build()).result().get(5, TimeUnit.SECONDS);
            assertEquals(StopReason.STOP, result.stopReason());
            assertEquals(3, server.requests.size());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 600);
        }
    }

    @Test
    void retriesServerErrorWithoutWaitingForStalledBody() throws Exception {
        try (var server = new FakeServer()) {
            var release = new CountDownLatch(1);
            server.enqueue(exchange -> {
                exchange.getResponseHeaders().set("Retry-After", "0");
                exchange.sendResponseHeaders(503, 0);
                release.await(8, TimeUnit.SECONDS);
            });
            server.fixture("text");
            try {
                var options = StreamOptions.builder().maxRetries(1).build();
                var result = provider.stream(server.model(), context, options).result().get(5, TimeUnit.SECONDS);
                assertEquals(StopReason.STOP, result.stopReason());
                assertEquals(2, server.requests.size());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void cancellationInterruptsRetryDelay() throws Exception {
        try (var server = new FakeServer()) {
            var received = new CountDownLatch(1);
            server.enqueue(exchange -> {
                exchange.getResponseHeaders().set("Retry-After", "10");
                FakeServer.send(exchange, 429, "rate limit");
                received.countDown();
            });
            var token = new CancellationToken();
            var stream = provider.stream(server.model(), context,
                    StreamOptions.builder().maxRetries(1).cancellation(token).build());
            assertTrue(received.await(3, TimeUnit.SECONDS));
            token.cancel();
            assertEquals(StopReason.ABORTED, stream.result().get(3, TimeUnit.SECONDS).stopReason());
            assertEquals(1, server.requests.size());
        }
    }

    @Test
    void requestAndIdleTimeoutsBecomeErrors() throws Exception {
        try (var server = new FakeServer()) {
            server.enqueue(exchange -> {
                Thread.sleep(1000);
                FakeServer.send(exchange, 200, "");
            });
            var result = provider.stream(server.model(), context,
                    StreamOptions.builder().requestTimeoutMs(100).build()).result().get(3, TimeUnit.SECONDS);
            assertEquals(StopReason.ERROR, result.stopReason());
            assertTrue(result.errorMessage().contains("timed out"));
            server.enqueue(exchange -> {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("data: {}\n\n".getBytes());
                exchange.getResponseBody().flush();
                Thread.sleep(1000);
            });
            result = provider.stream(server.model(), context,
                    StreamOptions.builder().idleTimeoutMs(100).build()).result().get(3, TimeUnit.SECONDS);
            assertEquals(StopReason.ERROR, result.stopReason());
            assertTrue(result.errorMessage().contains("idle timeout"));
        }
    }

    @Test
    void mergesHeadersAndSanitizesUnicode() throws Exception {
        try (var server = new FakeServer()) {
            server.fixture("text");
            var model = Model.builder("fake-model", server.baseUrl())
                    .headers(Map.of("X-Custom", "model", "X-Model", "kept")).build();
            var input = new Context("a\uD800b😀", List.of(new UserMessage("c\uDC00d")));
            var result = provider.stream(model, input, StreamOptions.builder()
                    .headers(Map.of("x-custom", "caller")).build()).result().get(5, TimeUnit.SECONDS);
            assertEquals(StopReason.STOP, result.stopReason());
            assertEquals("caller", server.headers.get(0).get("X-custom").get(0));
            assertEquals("kept", server.headers.get(0).get("X-model").get(0));
            assertTrue(server.requests.get(0).toString().contains("ab😀"));
            assertFalse(server.requests.get(0).toString().contains("\\udc00"));
        }
    }

    @Test
    void recognizesProviderOverflowAndExcludesRateLimits() {
        for (String text : List.of("exceeds the context window", "prompt is too long",
                "input token count exceeds the maximum", "context_length_exceeded", "413 oversized")) {
            var message = AssistantMessage.failure(Model.deepseek("test"), StopReason.ERROR, text);
            assertTrue(ContextOverflow.isContextOverflow(message));
        }
        var throttled = AssistantMessage.failure(Model.deepseek("test"), StopReason.ERROR,
                "rate limit: too many tokens");
        assertFalse(ContextOverflow.isContextOverflow(throttled));
    }

    @Test
    void eachProtocolSurfacesOverflow() throws Exception {
        List<AiProvider> providers = List.of(new OpenAiCompletions(HttpClient.newHttpClient()),
                new OpenAiResponses(HttpClient.newHttpClient()),
                new AnthropicMessages(HttpClient.newHttpClient()), new GoogleGenerativeAi(HttpClient.newHttpClient()));
        List<String> errors = List.of("exceeds the context window", "context_length_exceeded",
                "prompt is too long", "input token count exceeds the maximum");
        for (int i = 0; i < providers.size(); i++) {
            try (var server = new FakeServer("/")) {
                server.status(400, errors.get(i));
                var result = providers.get(i).stream(server.model(), context,
                        StreamOptions.builder().apiKey("fixture-key").build()).result().get(5, TimeUnit.SECONDS);
                assertTrue(ContextOverflow.isContextOverflow(result), result.errorMessage());
            }
        }
    }

    @Test
    void doesNotRetryAfterContent() throws Exception {
        try (var server = new FakeServer()) {
            server.enqueue(exchange -> {
                exchange.sendResponseHeaders(200, 0);
                String data = "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n";
                exchange.getResponseBody().write(data.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                Thread.sleep(1000);
            });
            var result = provider.stream(server.model(), context, StreamOptions.builder()
                    .maxRetries(2).idleTimeoutMs(100).build()).result().get(3, TimeUnit.SECONDS);
            assertEquals(StopReason.ERROR, result.stopReason());
            assertEquals("hello", result.text());
            assertEquals(1, server.requests.size());
        }
    }

    @Test
    void retriesIdleTimeoutBeforeContent() throws Exception {
        try (var server = new FakeServer()) {
            server.enqueue(exchange -> {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(": keepalive\n\n".getBytes());
                exchange.getResponseBody().flush();
                Thread.sleep(1000);
            });
            server.fixture("text");
            var result = provider.stream(server.model(), context, StreamOptions.builder()
                    .maxRetries(1).idleTimeoutMs(100).build()).result().get(4, TimeUnit.SECONDS);
            assertEquals(StopReason.STOP, result.stopReason());
            assertEquals(2, server.requests.size());
        }
    }

    @Test
    void retriesNetworkReadFailureBeforeContent() throws Exception {
        try (var server = new FakeServer()) {
            server.enqueue(exchange -> {
                exchange.sendResponseHeaders(200, 1000);
                exchange.getResponseBody().write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            });
            server.fixture("text");
            var result = provider.stream(server.model(), context, StreamOptions.builder()
                    .maxRetries(1).build()).result().get(5, TimeUnit.SECONDS);
            assertEquals(StopReason.STOP, result.stopReason());
            assertEquals(2, server.requests.size());
        }
    }

    @Test
    void doesNotRetryErrorContainingNetworkReadErrorText() throws Exception {
        var attempts = new AtomicInteger();
        var model = Model.deepseek("test");
        var stream = ProviderStreams.start(model, StreamOptions.builder().maxRetries(1).build(), (current, budget) -> {
            attempts.incrementAndGet();
            current.complete(AssistantMessage.failure(model, StopReason.ERROR, "Provider: Network read error"));
        });
        var result = stream.result().get(3, TimeUnit.SECONDS);
        assertEquals(StopReason.ERROR, result.stopReason());
        assertEquals("Provider: Network read error", result.errorMessage());
        assertEquals(1, attempts.get());
    }

}
