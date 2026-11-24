package io.github.izakyl.jpi.ai;

public enum StopReason {

    STOP,
    LENGTH,
    TOOL_USE,
    ERROR,
    ABORTED;

    public boolean isFailure() {
        return this == ERROR || this == ABORTED;
    }
}
