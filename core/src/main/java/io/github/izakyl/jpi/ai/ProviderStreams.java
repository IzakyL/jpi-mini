package io.github.izakyl.jpi.ai;

import io.github.izakyl.jpi.ai.internal.Workers;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;

final class ProviderStreams {

    private ProviderStreams() {
    }

    static AssistantMessageEventStream start(Model model, StreamOptions options,
            BiConsumer<AssistantMessageEventStream, RetryBudget> attempt) {
        var output = new AssistantMessageEventStream();
        var budget = new RetryBudget(options.maxRetries());
        Workers.executor().execute(() -> {
            for (;;) {
                budget.resetReadFailure();
                var current = new AssistantMessageEventStream();
                var started = new AssistantMessageEvent.Start[1];
                var emitted = new boolean[1];
                current.subscribe(event -> {
                    if (event instanceof AssistantMessageEvent.Start start) {
                        started[0] = start;
                    } else if (!(event instanceof AssistantMessageEvent.Done)
                            && !(event instanceof AssistantMessageEvent.Error)) {
                        if (!emitted[0] && started[0] != null) {
                            output.publish(started[0]);
                        }
                        emitted[0] = true;
                        output.publish(event);
                    }
                });
                attempt.accept(current, budget);
                AssistantMessage result = current.result().join();
                if (!emitted[0] && result.stopReason() == StopReason.ERROR && budget.readFailed() && budget.take()) {
                    try {
                        ProviderTransport.sleep(ProviderTransport.delay(budget.used() - 1), options.cancellation());
                        continue;
                    } catch (CancellationException cancellation) {
                        result = AssistantMessage.failure(model, StopReason.ABORTED, "Request was aborted");
                    } catch (IOException interrupted) {
                        result = AssistantMessage.failure(model, StopReason.ERROR, interrupted.getMessage());
                    }
                }
                if (!emitted[0] && started[0] != null) {
                    output.publish(started[0]);
                }
                output.complete(result);
                return;
            }
        });
        return output;
    }
}
