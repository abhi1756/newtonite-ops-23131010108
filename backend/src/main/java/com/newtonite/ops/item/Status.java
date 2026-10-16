package com.newtonite.ops.item;

public enum Status {
    OPEN,
    IN_PROGRESS,
    AWAITING_APPROVAL,
    RESOLVED,
    CLOSED;

    public boolean isFinished() {
        return this == RESOLVED || this == CLOSED;
    }
}
