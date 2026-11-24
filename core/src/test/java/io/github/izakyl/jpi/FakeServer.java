package io.github.izakyl.jpi;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.izakyl.jpi.ai.Model;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class FakeServer implements AutoCloseable {

    @FunctionalInterface
    public interface Reply {

        void send(HttpExchange exchange) throws Exception;
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    public final List<JsonObject> requests = new CopyOnWriteArrayList<>();
    public final List<String> authorization = new CopyOnWriteArrayList<>();
    public final List<Map<String, List<String>>> headers = new CopyOnWriteArrayList<>();
    public final List<String> paths = new CopyOnWriteArrayList<>();
    public final List<Throwable> failures = new CopyOnWriteArrayList<>();

    public FakeServer() throws IOException {
        this("/v1/chat/completions");
    }

    public FakeServer(String path) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext(
                path,
                exchange -> {
                    try (exchange) {
                        requests.add(
                                JsonParser.parseString(
                                                new String(
                                                        exchange.getRequestBody().readAllBytes(),
                                                        StandardCharsets.UTF_8))
                                        .getAsJsonObject());
                        authorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                        headers.add(Map.copyOf(exchange.getRequestHeaders()));
                        paths.add(exchange.getRequestURI().getPath());
                        var reply = replies.poll(5, TimeUnit.SECONDS);
                        if (reply == null) {
                            throw new IllegalStateException("Unexpected model request");
                        }
                        reply.send(exchange);
                    } catch (IOException ignored) {
                        // Aborted clients can close a socket while a response is being written.
                    } catch (Throwable error) {
                        failures.add(error);
                    }
                });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public Model model() {
        return Model.builder("fake-model", baseUrl()).build();
    }

    public void enqueue(Reply reply) {
        replies.add(reply);
    }

    public void fixture(String name) {
        enqueue(exchange -> send(exchange, 200, fixtureText(name)));
    }

    public void status(int status, String body) {
        enqueue(exchange -> send(exchange, status, body));
    }

    public static String fixtureText(String name) throws IOException {
        try (var input = FakeServer.class.getResourceAsStream("/sse/" + name + ".sse")) {
            if (input == null) {
                throw new IllegalArgumentException("Missing fixture " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static void send(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", status == 200 ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(status, 0);
        exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
