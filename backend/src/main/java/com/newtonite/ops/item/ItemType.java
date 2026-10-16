package com.newtonite.ops.item;

public enum ItemType {
    INCIDENT,
    CUSTOMER_ISSUE,
    ENGINEERING,
    PAYMENT,
    COMPLIANCE,
    TASK;

    /** Money movement and regulatory work need a Lead's sign-off before they count as resolved. */
    public boolean requiresApproval() {
        return this == PAYMENT || this == COMPLIANCE;
    }
}
