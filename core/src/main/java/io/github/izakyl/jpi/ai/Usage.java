package io.github.izakyl.jpi.ai;

/**
 * Input excludes cache reads/writes; output includes reasoning tokens. Prices are per million
 * tokens.
 */
public record Usage(long input, long output, long cacheRead, long cacheWrite, long reasoning, long totalTokens,
        Cost cost) {

    public static final Usage ZERO = new Usage(0, 0, 0, 0, 0, 0, Cost.ZERO);

    public record Cost(double input, double output, double cacheRead, double cacheWrite, double total) {

        public static final Cost ZERO = new Cost(0, 0, 0, 0, 0);
    }
}
