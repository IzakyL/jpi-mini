package io.github.izakyl.jpi.ai;

import io.github.izakyl.jpi.ai.internal.Workers;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLSession;

final class ProviderTransport {

    private ProviderTransport() {
    }

    static HttpResponse<InputStream> send(HttpClient client, HttpRequest request, StreamOptions options,
            RetryBudget budget)
            throws IOException {
        requireSecureEndpoint(request.uri());
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IOException("Provider HTTP client must disable redirects");
        }
        for (int attempt = 0; ; attempt++) {
            options.cancellation().throwIfCancelled();
            budget.resetReadFailure();
            long started = System.nanoTime();
            var future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<InputStream> response;
            try (var subscription = options.cancellation().onCancel(() -> future.cancel(true))) {
                try {
                    response = options.requestTimeoutMs() == 0 ? future.get()
                            : future.get(options.requestTimeoutMs(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException error) {
                    future.cancel(true);
                    if (budget.take()) {
                        sleep(delay(attempt), options.cancellation());
                        continue;
                    }
                    throw new IOException("Request timed out", error);
                } catch (InterruptedException error) {
                    future.cancel(true);
                    Thread.currentThread().interrupt();
                    throw new IOException("Request interrupted", error);
                } catch (ExecutionException error) {
                    if (budget.take()) {
                        sleep(delay(attempt), options.cancellation());
                        continue;
                    }
                    throw new IOException("Network error", error.getCause());
                }
            }
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                var buffered = new PushbackInputStream(timed(response.body(), options, started, budget));
                try (var subscription = options.cancellation().onCancel(() -> close(buffered))) {
                    try {
                        int first = buffered.read();
                        if (first >= 0) {
                            buffered.unread(first);
                        }
                        return new BodyResponse(response, buffered);
                    } catch (IOException error) {
                        close(buffered);
                        options.cancellation().throwIfCancelled();
                        if (!budget.take()) {
                            throw error;
                        }
                        sleep(delay(attempt), options.cancellation());
                        continue;
                    }
                }
            }
            String body = errorBody(response.body(), options, started, status);
            if (!retryable(status, response.headers(), body) || !budget.take()) {
                throw new HttpFailure(status, ContextOverflow.matchesError("HTTP " + status + ": " + body));
            }
            long wait = retryDelay(response.headers(), attempt);
            if (options.maxRetryDelayMs() > 0 && wait > options.maxRetryDelayMs()) {
                throw new IOException("Server requested retry delay exceeds maximum (HTTP " + status + ")");
            }
            sleep(wait, options.cancellation());
        }
    }

    private static void requireSecureEndpoint(URI uri) {
        String host = uri.getHost();
        String scheme = uri.getScheme();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        if (host == null || uri.getRawUserInfo() != null
                || !("https".equalsIgnoreCase(scheme) || ("http".equalsIgnoreCase(scheme) && loopback))) {
            throw new IllegalArgumentException("Provider endpoint must use HTTPS or loopback HTTP");
        }
    }

    private static String errorBody(InputStream input, StreamOptions options, long started, int status) {
        // Inspect bodies only for quota and context-window classification.
        if (status != 400 && status != 413 && status != 429) {
            close(input);
            options.cancellation().throwIfCancelled();
            return "";
        }
        long timeout = 1000;
        if (options.requestTimeoutMs() > 0) {
            timeout = Math.min(timeout, Math.max(1, options.requestTimeoutMs()
                    - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
        }
        if (options.idleTimeoutMs() > 0) {
            timeout = Math.min(timeout, options.idleTimeoutMs());
        }
        var deadline = Workers.schedule(() -> close(input), timeout, TimeUnit.MILLISECONDS);
        try (input; var subscription = options.cancellation().onCancel(() -> close(input))) {
            String body = new String(input.readNBytes(65536), StandardCharsets.UTF_8);
            options.cancellation().throwIfCancelled();
            return body;
        } catch (IOException ignored) {
            options.cancellation().throwIfCancelled();
            return "";
        } finally {
            deadline.cancel(false);
        }
    }

    static final class HttpFailure extends IOException {
        final int status;
        final boolean contextOverflow;

        HttpFailure(int status, boolean contextOverflow) {
            super("HTTP " + status + (contextOverflow ? ": context_length_exceeded" : ""));
            this.status = status;
            this.contextOverflow = contextOverflow;
        }
    }

    private static boolean retryable(int status, HttpHeaders headers, String body) {
        String directive = headers.firstValue("x-should-retry").orElse("");
        if (directive.equalsIgnoreCase("true")) {
            return true;
        }
        if (directive.equalsIgnoreCase("false")) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.matches("(?s).*(insufficient_quota|quota exceeded|billing|out of budget).*")) {
            return false;
        }
        return status == 408 || status == 409 || status == 429 || status >= 500;
    }

    static long delay(int attempt) {
        return (long) (Math.min(500L << Math.min(attempt, 4), 8000) * (1 - Math.random() * 0.25));
    }

    private static long retryDelay(HttpHeaders headers, int attempt) {
        String millis = headers.firstValue("retry-after-ms").orElse("");
        try {
            if (!millis.isBlank()) {
                return Math.max(0, (long) Double.parseDouble(millis));
            }
            String value = headers.firstValue("retry-after").orElse("");
            if (!value.isBlank()) {
                try {
                    return Math.max(0, (long) (Double.parseDouble(value) * 1000));
                } catch (NumberFormatException ignored) {
                    return Math.max(0, Duration.between(ZonedDateTime.now(),
                            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)).toMillis());
                }
            }
        } catch (RuntimeException ignored) {
        }
        return delay(attempt);
    }

    static void sleep(long millis, CancellationToken cancellation) throws IOException {
        Object lock = new Object();
        try (var subscription = cancellation.onCancel(() -> {
            synchronized (lock) {
                lock.notifyAll();
            }
        })) {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            synchronized (lock) {
                while (!cancellation.isCancelled()) {
                    long remaining = end - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Retry interrupted", error);
        }
        cancellation.throwIfCancelled();
    }

    private static void close(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }

    private static InputStream timed(InputStream input, StreamOptions options, long started, RetryBudget budget) {
        return new FilterInputStream(input) {
            private boolean first = true;

            @Override
            public int read() throws IOException {
                byte[] buffer = new byte[1];
                int count = read(buffer, 0, 1);
                return count < 0 ? -1 : buffer[0] & 255;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                long timeout = first && options.requestTimeoutMs() > 0
                        ? Math.max(1, options.requestTimeoutMs()
                                - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                        : options.idleTimeoutMs();
                Object lock = new Object();
                boolean[] finished = {false};
                boolean[] timedOut = {false};
                var task = timeout == 0 ? null : Workers.schedule(() -> {
                    synchronized (lock) {
                        if (finished[0]) {
                            return;
                        }
                        timedOut[0] = true;
                    }
                    ProviderTransport.close(in);
                }, timeout, TimeUnit.MILLISECONDS);
                int count = -1;
                IOException failure = null;
                try {
                    count = in.read(bytes, offset, length);
                } catch (IOException error) {
                    failure = error;
                } finally {
                    synchronized (lock) {
                        finished[0] = true;
                        if (task != null) {
                            task.cancel(false);
                        }
                    }
                }
                if (timedOut[0]) {
                    budget.markReadFailure();
                    throw new IOException(first ? "Request timed out" : "Stream idle timeout", failure);
                }
                if (failure != null) {
                    budget.markReadFailure();
                    throw new IOException("Network read error", failure);
                }
                first = false;
                return count;
            }
        };
    }

    private record BodyResponse(HttpResponse<InputStream> original, InputStream body)
            implements HttpResponse<InputStream> {
        @Override
        public int statusCode() {
            return original.statusCode();
        }

        @Override
        public HttpRequest request() {
            return original.request();
        }

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return original.previousResponse();
        }

        @Override
        public HttpHeaders headers() {
            return original.headers();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return original.sslSession();
        }

        @Override
        public URI uri() {
            return original.uri();
        }

        @Override
        public HttpClient.Version version() {
            return original.version();
        }
    }
}
