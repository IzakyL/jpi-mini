package io.github.izakyl.jpi.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.izakyl.jpi.FakeServer;
import io.github.izakyl.jpi.ai.AiProvider;
import io.github.izakyl.jpi.ai.AssistantMessage;
import io.github.izakyl.jpi.ai.AssistantMessageEventStream;
import io.github.izakyl.jpi.ai.CancellationToken;
import io.github.izakyl.jpi.ai.ContentBlock;
import io.github.izakyl.jpi.ai.ProviderRegistry;
import io.github.izakyl.jpi.ai.StopReason;
import io.github.izakyl.jpi.ai.StreamOptions;
import io.github.izakyl.jpi.ai.ToolResultMessage;
import io.github.izakyl.jpi.ai.UserMessage;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class AgentTest {

    FakeServer server;
    final StreamOptions options = new StreamOptions("key");
    final AtomicInteger executions = new AtomicInteger();
    AgentTool weather;

    @BeforeEach
    void setup() throws Exception {
        server = new FakeServer();
        weather =
                tool(
                        (id, args, cancel, update) -> {
                            executions.incrementAndGet();
                            assertEquals("Paris", args.get("city").getAsString());
                            update.accept(AgentToolResult.text("Looking up weather"));
                            return new AgentToolResult(
                                    List.of(new ContentBlock.Text("Sunny in Paris")), json("{\"temperature\":22}"));
                        });
    }

    @AfterEach
    void cleanup() {
        server.close();
        assertEquals(List.of(), server.failures);
    }

    @Test
    void toolRoundTripEventsStateAndIdle() throws Exception {
        server.fixture("tool");
        server.fixture("final");
        var agent = new Agent(server.model(), "Answer using tools", List.of(weather), options);
        var events = new CopyOnWriteArrayList<AgentEvent>();
        var callbackState = new CopyOnWriteArrayList<Boolean>();
        agent.subscribe(
                event -> {
                    events.add(event);
                    callbackState.add(agent.isStreaming());
                });
        var run = agent.prompt("Weather in Paris?");
        assertTrue(agent.isStreaming());
        var idle = agent.waitForIdle();
        var added = run.get(5, TimeUnit.SECONDS);
        idle.get(5, TimeUnit.SECONDS);
        assertFalse(agent.isStreaming());
        assertEquals(1, executions.get());
        assertEquals(4, added.size());
        assertEquals(added, agent.state().messages());
        assertEquals("It is sunny.", ((AssistantMessage) added.get(added.size() - 1)).text());
        assertEquals(2, server.requests.size());
        var second = server.requests.get(1).getAsJsonArray("messages");
        var result = second.get(second.size() - 1).getAsJsonObject();
        assertEquals("tool", result.get("role").getAsString());
        assertEquals("call_weather", result.get("tool_call_id").getAsString());
        assertEquals("Sunny in Paris", result.get("content").getAsString());
        assertFalse(result.has("details"));
        assertEquals(22, ((ToolResultMessage) added.get(2)).details().getAsJsonObject().get("temperature").getAsInt());
        var types = events.stream().map(AgentEvent::type).toList();
        assertEquals("agent_start", types.get(0));
        assertEquals("agent_end", types.get(types.size() - 1));
        assertEquals(2, Collections.frequency(types, "turn_start"));
        assertEquals(2, Collections.frequency(types, "turn_end"));
        assertEquals(4, Collections.frequency(types, "message_start"));
        assertEquals(4, Collections.frequency(types, "message_end"));
        assertTrue(types.indexOf("tool_execution_start") < types.indexOf("tool_execution_update"));
        assertTrue(types.indexOf("tool_execution_update") < types.indexOf("tool_execution_end"));
        assertTrue(callbackState.stream().allMatch(Boolean::booleanValue));
        assertNull(agent.state().streamingMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-tool", "malformed-tool", "length-tool"})
    void invalidArgumentsAndTruncatedCallsBecomeModelVisibleErrors(String fixture) throws Exception {
        server.fixture(fixture);
        server.fixture("final");
        var agent = new Agent(server.model(), "", List.of(weather), options);
        var messages = agent.prompt("weather").get(5, TimeUnit.SECONDS);
        assertEquals(0, executions.get());
        var result = (ToolResultMessage) messages.get(2);
        assertTrue(result.isError());
        var body = server.requests.get(1).getAsJsonArray("messages");
        var feedback = body.get(body.size() - 1).getAsJsonObject().get("content").getAsString();
        assertFalse(feedback.isBlank());
        if (fixture.equals("invalid-tool")) {
            assertTrue(feedback.contains("required"));
        }
        if (fixture.equals("malformed-tool")) {
            assertTrue(feedback.contains("Invalid tool argument JSON"));
        }
        if (fixture.equals("length-tool")) {
            assertTrue(feedback.contains("truncated"));
        }
        assertEquals("It is sunny.", ((AssistantMessage) messages.get(messages.size() - 1)).text());
    }

    @Test
    void unknownToolsAndExecutionExceptionsBecomeErrorResults() throws Exception {
        for (var tools :
                List.of(
                        List.<AgentTool>of(),
                        List.of(
                                tool(
                                        (id, args, cancel, update) -> {
                                            throw new IllegalStateException("lookup failed");
                                        })))) {
            server.fixture("tool");
            server.fixture("final");
            var agent = new Agent(server.model(), "", tools, options);
            var result = (ToolResultMessage) agent.prompt("go").get(5, TimeUnit.SECONDS).get(2);
            assertTrue(result.isError());
            String text = ((ContentBlock.Text) result.content().get(0)).text();
            assertTrue(text.contains(tools.isEmpty() ? "not found" : "lookup failed"));
        }
    }

    @Test
    void twoToolCallsAreExecutedAndAllResultsReplayed() throws Exception {
        server.fixture("parallel");
        server.fixture("final");
        var names = new CopyOnWriteArrayList<String>();
        var tools =
                List.of(
                        tool(
                                (id, args, cancel, update) -> {
                                    names.add(args.get("city").getAsString());
                                    return AgentToolResult.text("Result for " + id);
                                }));
        var agent = new Agent(server.model(), "", tools, options);
        var messages = agent.prompt("go").get(5, TimeUnit.SECONDS);
        assertEquals(List.of("Paris", "Tokyo"), names);
        assertEquals(5, messages.size());
        var replay = server.requests.get(1).getAsJsonArray("messages");
        assertEquals("a", replay.get(replay.size() - 2).getAsJsonObject().get("tool_call_id").getAsString());
        assertEquals("b", replay.get(replay.size() - 1).getAsJsonObject().get("tool_call_id").getAsString());
    }

    @Test
    void steeringThenFollowUpQueuesTriggerFurtherTurns() throws Exception {
        server.fixture("final");
        server.fixture("final");
        server.fixture("final");
        var agent = new Agent(server.model(), "", List.of(), options);
        var injected = new AtomicBoolean();
        agent.subscribe(
                event -> {
                    if (event instanceof AgentEvent.TurnEnd && injected.compareAndSet(false, true)) {
                        agent.steer(new UserMessage("Steer now"));
                        agent.followUp(new UserMessage("Then follow up"));
                    }
                });
        var messages = agent.prompt("initial").get(5, TimeUnit.SECONDS);
        assertEquals(3, server.requests.size());
        assertEquals(
                List.of("initial", "Steer now", "Then follow up"),
                messages.stream()
                        .filter(UserMessage.class::isInstance)
                        .map(m -> ((ContentBlock.Text) m.content().get(0)).text())
                        .toList());
    }

    @Test
    void lowLevelContinuationAndEmptyContextGuards() throws Exception {
        server.fixture("final");
        var old = new UserMessage("existing");
        var context = new AgentContext("", List.of(old), List.of());
        var config = new AgentLoopConfig(server.model(), ProviderRegistry.defaults(), options);
        var produced = AgentLoop.agentLoopContinue(context, config).result().get(5, TimeUnit.SECONDS);
        assertEquals(1, produced.size());
        assertInstanceOf(AssistantMessage.class, produced.get(0));
        assertEquals(List.of(old), context.messages());
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentLoop.agentLoopContinue(new AgentContext("", List.of(), List.of()), config));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentLoop.agentLoopContinue(new AgentContext("", produced, List.of()), config));
        var agent = new Agent(server.model(), "", List.of(), options);
        assertThrows(IllegalArgumentException.class, agent::continueRun);
        assertFalse(agent.isStreaming());
    }

    @Test
    void providerFailureEndsAgentAndAllowsNextPrompt() throws Exception {
        server.status(500, "broken");
        server.fixture("final");
        var agent = new Agent(server.model(), "", List.of(), options);
        var first = agent.prompt("first").get(5, TimeUnit.SECONDS);
        assertEquals(StopReason.ERROR, ((AssistantMessage) first.get(first.size() - 1)).stopReason());
        assertFalse(agent.isStreaming());
        agent.setSystemPrompt("new prompt");
        var second = agent.prompt("retry").get(5, TimeUnit.SECONDS);
        assertEquals(StopReason.STOP, ((AssistantMessage) second.get(second.size() - 1)).stopReason());
        assertEquals(4, agent.state().messages().size());
    }

    @Test
    void abortReleasesAgentWaitingOnUncooperativeProvider() throws Exception {
        var requested = new CountDownLatch(1);
        var requests = new AtomicInteger();
        AiProvider provider = (model, context, options) -> {
            var producer = AssistantMessageEventStream.producer();
            if (requests.incrementAndGet() == 1) {
                requested.countDown();
            } else {
                producer.complete(AssistantMessage.builder(model).build());
            }
            return producer.stream();
        };
        var agent = new Agent(server.model(), "", List.of(), options, provider);
        var first = agent.prompt("stall");
        assertTrue(requested.await(5, TimeUnit.SECONDS));
        agent.abort();
        assertEquals(StopReason.ABORTED, ((AssistantMessage) first.get(5, TimeUnit.SECONDS).get(1)).stopReason());
        assertFalse(agent.isStreaming());
        var next = agent.prompt("recover").get(5, TimeUnit.SECONDS);
        assertEquals(StopReason.STOP, ((AssistantMessage) next.get(1)).stopReason());
    }

    @Test
    void transformsEachRequestAndLimitsRepeatedToolTurns() throws Exception {
        var requests = new AtomicInteger();
        var transforms = new AtomicInteger();
        AiProvider provider = (model, context, options) -> {
            assertEquals(List.of("filtered"), context.messages().stream()
                    .map(UserMessage.class::cast).map(message -> ((ContentBlock.Text) message.content().get(0)).text())
                    .toList());
            requests.incrementAndGet();
            var producer = AssistantMessageEventStream.producer();
            producer.complete(AssistantMessage.builder(model).stopReason(StopReason.LENGTH)
                    .content(List.of(new ContentBlock.ToolCall("call", "weather", new JsonObject()))).build());
            return producer.stream();
        };
        var config = new AgentLoopConfig(server.model(), provider, options, null, null, messages -> {
            transforms.incrementAndGet();
            return List.of(new UserMessage("filtered"));
        }, 2);
        var original = new UserMessage("original");
        var run = AgentLoop.agentLoop(List.of(original), new AgentContext("", List.of(), List.of(weather)), config);
        var messages = run.result().get(5, TimeUnit.SECONDS);
        assertEquals(original, messages.get(0));
        assertEquals(2, requests.get());
        assertEquals(2, transforms.get());
        assertEquals(0, executions.get());
        var failure = (AssistantMessage) messages.get(messages.size() - 1);
        assertEquals(StopReason.ERROR, failure.stopReason());
        assertEquals("Agent turn limit reached (2)", failure.errorMessage());
    }

    @Test
    void synchronousProvidersAndFailuresFollowLifecycle() throws Exception {
        AiProvider synchronous =
                (model, context, options) -> {
                    var result = AssistantMessageEventStream.producer();
                    result.complete(AssistantMessage.builder(model)
                            .content(List.of(new ContentBlock.Text("instant"))).build());
                    return result.stream();
                };
        var agent = new Agent(server.model(), "", List.of(), options, synchronous);
        var events = new CopyOnWriteArrayList<AgentEvent>();
        agent.subscribe(events::add);
        assertEquals("instant", ((AssistantMessage) agent.prompt("go").get(5, TimeUnit.SECONDS).get(1)).text());
        assertEquals(2, events.stream().filter(AgentEvent.MessageStart.class::isInstance).count());
        var broken =
                new Agent(
                        server.model(),
                        "",
                        List.of(),
                        options,
                        (m, c, o) -> {
                            throw new IllegalStateException("setup failed");
                        });
        assertEquals(
                StopReason.ERROR,
                ((AssistantMessage) broken.prompt("go").get(5, TimeUnit.SECONDS).get(1)).stopReason());
    }

    @Test
    void abortDuringToolExecutionSettlesRunAndRejectsConcurrentPrompt() throws Exception {
        server.fixture("tool");
        var executing = new CountDownLatch(1);
        var cancellable =
                tool(
                        (id, args, cancel, update) -> {
                            var stopped = new CountDownLatch(1);
                            try (var ignored = cancel.onCancel(stopped::countDown)) {
                                executing.countDown();
                                if (!stopped.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("test did not abort");
                                }
                                cancel.throwIfCancelled();
                                return AgentToolResult.text("unreachable");
                            }
                        });
        var agent = new Agent(server.model(), "", List.of(cancellable), options);
        var run = agent.prompt("go");
        assertTrue(executing.await(5, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> agent.prompt("concurrent"));
        assertThrows(IllegalStateException.class, () -> agent.replaceMessages(List.of()));
        agent.abort();
        var messages = run.get(5, TimeUnit.SECONDS);
        assertEquals(StopReason.ABORTED, ((AssistantMessage) messages.get(messages.size() - 1)).stopReason());
        assertFalse(agent.isStreaming());
        assertEquals(1, server.requests.size());
    }

    @Test
    void listenerExceptionsAndUnsubscriptionDoNotBreakAgent() throws Exception {
        server.fixture("final");
        var agent = new Agent(server.model(), "", List.of(), options);
        agent.subscribe(
                event -> {
                    throw new IllegalStateException("listener");
                });
        var count = new AtomicInteger();
        var subscription = agent.subscribe(event -> count.incrementAndGet());
        subscription.close();
        agent.prompt("go").get(5, TimeUnit.SECONDS);
        assertEquals(0, count.get());
        assertFalse(agent.isStreaming());
    }

    @Test
    void continuationUpdatesStateAndToolErrorsCanBeReturnedExplicitly() throws Exception {
        server.fixture("tool");
        server.fixture("final");
        var failed = tool((id, args, cancel, update) -> AgentToolResult.error("unavailable"));
        var agent = new Agent(server.model(), "", List.of(failed), options);
        agent.replaceMessages(List.of(new UserMessage("saved user")));
        var run = agent.continueRun().get(5, TimeUnit.SECONDS);
        assertEquals(3, run.size());
        assertTrue(((ToolResultMessage) run.get(1)).isError());
        assertEquals(4, agent.state().messages().size());
        assertThrows(IllegalArgumentException.class, () -> agent.setTools(List.of(failed, failed)));
    }

    private AgentTool tool(Execution execution) {
        return new AgentTool() {

            @Override
            public String name() {
                return "weather";
            }

            @Override
            public String label() {
                return "Weather";
            }

            @Override
            public String description() {
                return "Look up weather";
            }

            @Override
            public JsonObject parameters() {
                return json(
                        "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"},"
                                + "\"days\":{\"type\":\"integer\"}},\"required\":[\"city\"]}");
            }

            @Override
            public AgentToolResult execute(String id, JsonObject args, CancellationToken cancel,
                    Consumer<AgentToolResult> update) throws Exception {
                return execution.run(id, args, cancel, update);
            }
        };
    }

    @FunctionalInterface
    private interface Execution {

        AgentToolResult run(String id, JsonObject args, CancellationToken cancel,
                Consumer<AgentToolResult> update) throws Exception;
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
