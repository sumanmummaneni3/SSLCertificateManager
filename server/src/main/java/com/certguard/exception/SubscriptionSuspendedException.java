package com.certguard.exception;

public class SubscriptionSuspendedException extends RuntimeException {
    public SubscriptionSuspendedException(String orgName) {
        super("Scanning is suspended for organisation: " + orgName);
    }
}
