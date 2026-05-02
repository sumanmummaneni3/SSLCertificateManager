package com.certguard.exception;

/**
 * Thrown when a bundle download token has expired or has already been consumed.
 * Mapped to HTTP 410 Gone by GlobalExceptionHandler.
 */
public class BundleExpiredException extends RuntimeException {
    public BundleExpiredException(String message) {
        super(message);
    }
}
