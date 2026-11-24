package io.github.izakyl.jpi.ai;

final class RetryBudget {

    private int remaining;
    private int used;
    private boolean readFailed;

    RetryBudget(int retries) {
        remaining = retries;
    }

    synchronized void resetReadFailure() {
        readFailed = false;
    }

    synchronized void markReadFailure() {
        readFailed = true;
    }

    synchronized boolean readFailed() {
        return readFailed;
    }

    synchronized boolean take() {
        if (remaining == 0) {
            return false;
        }
        remaining--;
        used++;
        return true;
    }

    synchronized int used() {
        return used;
    }
}
