package com.certguard.exception;
public class QuotaExceededException extends RuntimeException {
    public QuotaExceededException(String message) { super(message); }
}
