package com.certguard.renewal.ca;

public class CaProviderException extends RuntimeException {
    public CaProviderException(String message) { super(message); }
    public CaProviderException(String message, Throwable cause) { super(message, cause); }
}
