/**
 * Plain Java LLM types and streaming providers. Messages and event partials are snapshots. Stream
 * listeners run serially on library worker threads, including replay to late subscribers. Listeners
 * must not block on the same stream's result. Runtime exceptions from listeners are isolated.
 * Marshal callbacks to the consumer's own thread when required.
 */
package io.github.izakyl.jpi.ai;
