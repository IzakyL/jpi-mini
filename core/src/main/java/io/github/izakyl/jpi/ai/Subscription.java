package io.github.izakyl.jpi.ai;

@FunctionalInterface
public interface Subscription extends AutoCloseable {

    @Override
    void close();
}
