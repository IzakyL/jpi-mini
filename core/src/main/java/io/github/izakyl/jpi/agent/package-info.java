/**
 * Plain Java agent loop and stateful facade. Tools execute on workers and must cooperate with
 * cancellation. Agent listeners run serially on workers; turn-boundary callbacks settle before
 * queue polling. Do not block a listener on prompt() or waitForIdle() for its own run. Consumers
 * own thread marshalling.
 */
package io.github.izakyl.jpi.agent;
