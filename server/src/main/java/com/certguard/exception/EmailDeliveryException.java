package com.certguard.exception;

/**
 * Thrown by EmailDispatchService when SMTP delivery fails in non-dev mode.
 * Callers may catch this to return an appropriate HTTP 5xx response.
 */
public class EmailDeliveryException extends RuntimeException {
    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
